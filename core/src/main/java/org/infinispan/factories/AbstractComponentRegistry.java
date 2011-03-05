/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2000 - 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.factories;

import org.infinispan.CacheException;
import org.infinispan.Version;
import org.infinispan.config.Configuration;
import org.infinispan.config.ConfigurationException;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.DefaultFactoryFor;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.factories.annotations.SurvivesRestarts;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.lifecycle.Lifecycle;
import org.infinispan.lifecycle.ModuleLifecycle;
import org.infinispan.manager.ReflectionCache;
import org.infinispan.util.BeanUtils;
import org.infinispan.util.ModuleProperties;
import org.infinispan.util.ReflectionUtil;
import org.infinispan.util.logging.Log;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * A registry where components which have been created are stored.  Components are stored as singletons, registered
 * under a specific name.
 * <p/>
 * Components can be retrieved from the registry using {@link #getComponent(Class)}.
 * <p/>
 * Components can be registered using {@link #registerComponent(Object, Class)}, which will cause any dependencies to be
 * wired in as well.  Components that need to be created as a result of wiring will be done using {@link
 * #getOrCreateComponent(Class)}, which will look up the default factory for the component type (factories annotated
 * with the appropriate {@link DefaultFactoryFor} annotation.
 * <p/>
 * Default factories are treated as components too and will need to be wired before being used.
 * <p/>
 * The registry can exist in one of several states, as defined by the {@link org.infinispan.lifecycle.ComponentStatus}
 * enumeration. In terms of the cache, state changes in the following manner: <ul> <li>INSTANTIATED - when first
 * constructed</li> <li>CONSTRUCTED - when created using the DefaultCacheFactory</li> <li>STARTED - when {@link
 * org.infinispan.Cache#start()} is called</li> <li>STOPPED - when {@link org.infinispan.Cache#stop()} is called</li>
 * </ul>
 * <p/>
 * Cache configuration can only be changed and will only be re-injected if the cache is not in the {@link
 * org.infinispan.lifecycle.ComponentStatus#RUNNING} state.
 *
 * @author Manik Surtani
 * @author Galder Zamarreño
 * @since 4.0
 */
@SurvivesRestarts
@Scope(Scopes.NAMED_CACHE)
public abstract class AbstractComponentRegistry implements Lifecycle, Cloneable {

   // Make sure this is ALWAYS false when being checked in to the code repository!
   public static final boolean DEBUG_DEPENDENCIES = false;
   private Stack<String> debugStack = DEBUG_DEPENDENCIES ? new Stack<String>() : null;

   /**
    * Contains class definitions of component factories that can be used to construct certain components
    */
   private Map<Class, Class<? extends AbstractComponentFactory>> defaultFactories = null;

   protected static final Object NULL_COMPONENT = new Object();

   // component and method containers
   final Map<String, Component> componentLookup = new HashMap<String, Component>();

   protected static List<ModuleLifecycle> moduleLifecycles = ModuleProperties.resolveModuleLifecycles();

   volatile ComponentStatus state = ComponentStatus.INSTANTIATED;

   final ReflectionCache reflectionCache;

   public AbstractComponentRegistry(ReflectionCache reflectionCache) {
      this.reflectionCache = reflectionCache;
   }

   /**
    * Retrieves the state of the registry
    *
    * @return state of the registry
    */
   public ComponentStatus getStatus() {
      return state;
   }

   protected abstract Log getLog();

   /**
    * Wires an object instance with dependencies annotated with the {@link Inject} annotation, creating more components
    * as needed based on the Configuration passed in if these additional components don't exist in the {@link
    * ComponentRegistry}.  Strictly for components that don't otherwise live in the registry and have a lifecycle, such
    * as Commands.
    *
    * @param target object to wire
    *
    * @throws ConfigurationException if there is a problem wiring the instance
    */
   public void wireDependencies(Object target) throws ConfigurationException {
      try {
         // don't use the reflection cache for wireDependencies calls since these are not managed by the ComponentRegistry
         // and may be invoked at any time, even after the cache starts.
         List<Method> methods = getAllMethodsViaReflection(target.getClass(), Inject.class);

         // search for anything we need to inject
         for (Method method : methods) invokeInjectionMethod(target, method);
      }
      catch (Exception e) {
         throw new ConfigurationException("Unable to configure component (type: " + target.getClass() + ", instance " + target + ")", e);
      }
   }

   /**
    * This is hard coded for now, since scanning the classpath for factories annotated with {@link DefaultFactoryFor}
    * does not work with all class loaders.  This is a temporary solution until a more elegant one can be designed.
    * <p/>
    * BE SURE TO ADD ANY NEW FACTORY TYPES ANNOTATED WITH DefaultFactoryFor TO THIS SET!!
    * <p/>
    *
    * @return set of known factory types.
    */
   private Set<Class<? extends AbstractComponentFactory>> getHardcodedFactories() {
      Set<Class<? extends AbstractComponentFactory>> s = new HashSet<Class<? extends AbstractComponentFactory>>();
      s.add(BootstrapFactory.class);
      s.add(EmptyConstructorNamedCacheFactory.class);
      s.add(EmptyConstructorFactory.class);
      s.add(InterceptorChainFactory.class);
      s.add(RpcManagerFactory.class);
      s.add(TransactionManagerFactory.class);
      s.add(ReplicationQueueFactory.class);
      s.add(StateTransferManagerFactory.class);
      s.add(LockManagerFactory.class);
      s.add(DataContainerFactory.class);
      s.add(NamedExecutorsFactory.class);
      s.add(TransportFactory.class);
      s.add(MarshallerFactory.class);
      s.add(ResponseGeneratorFactory.class);
      s.add(DistributionManagerFactory.class);
      return s;
   }

   /**
    * Registers a component in the registry under the given type, and injects any dependencies needed.  If a component
    * of this type already exists, it is overwritten.
    *
    * @param component component to register
    * @param type      type of component
    */
   public void registerComponent(Object component, Class type) {
      registerComponent(component, type.getName());
   }

   public void registerComponent(Object component, String name) {
      if (component == null)
         throw new NullPointerException("Cannot register a null component under name [" + name + "]");
      Component old = componentLookup.get(name);

      if (old != null) {
         // if they are equal don't bother
         if (old.instance.equals(component)) {
            getLog().trace("Attempting to register a component equal to one that already exists under the same name (%s).  Not doing anything.", name);
            return;
         }
      }

      Component c;
      if (old != null) {
         getLog().trace("Replacing old component %s with new instance %s", old, component);
         old.instance = component;
         old.methodsScanned = false;
         c = old;

         if (state == ComponentStatus.RUNNING) populateLifecycleMethods();
      } else {
         c = new Component();
         c.name = name;
         c.instance = component;
         componentLookup.put(name, c);
      }
      c.nonVolatile = ReflectionUtil.isAnnotationPresent(component.getClass(), SurvivesRestarts.class);
      addComponentDependencies(c);
      // inject dependencies for this component
      c.injectDependencies();

      if (old == null) getLog().trace("Registering component %s under name %s", c, name);
      if (state == ComponentStatus.RUNNING) populateLifeCycleMethods(c);
   }

   /**
    * Adds component dependencies for a given component, by populating {@link Component#injectionMethods}.
    *
    * @param c component to add dependencies to
    */
   protected void addComponentDependencies(Component c) {
      Class type = c.instance.getClass();
      List<Method> methods = getAllMethodsViaReflection(type, Inject.class);
      c.injectionMethods.clear();
      c.injectionMethods.addAll(methods);
   }

   @SuppressWarnings("unchecked")
   protected void invokeInjectionMethod(Object o, Method m) {
      Class[] dependencies = m.getParameterTypes();
      Annotation[][] parameterAnnotations = m.getParameterAnnotations();
      Object[] params = new Object[dependencies.length];
      if (getLog().isTraceEnabled())
         getLog().trace("Injecting dependencies for method [%s] on an instance of [%s].", m, o.getClass().getName());
      for (int i = 0; i < dependencies.length; i++) {
         params[i] = getOrCreateComponent(dependencies[i], getComponentName(dependencies[i], parameterAnnotations, i));
      }

      ReflectionUtil.invokeAccessibly(o, m, params);
   }

   private String getComponentName(Class component, Annotation[][] annotations, int paramNumber) {
      String name;
      if (annotations == null ||
              annotations.length <= paramNumber ||
              (name = findComponentName(annotations[paramNumber])) == null) return component.getName();

      return name;
   }

   private String findComponentName(Annotation[] annotations) {
      if (annotations != null && annotations.length > 0) {
         for (Annotation a : annotations) {
            if (a instanceof ComponentName) {
               return ((ComponentName) a).value();
            }
         }
      }
      return null;
   }

   /**
    * Retrieves a component if one exists, and if not, attempts to find a factory capable of constructing the component
    * (factories annotated with the {@link DefaultFactoryFor} annotation that is capable of creating the component
    * class).
    * <p/>
    * If an instance needs to be constructed, dependencies are then automatically wired into the instance, based on
    * methods on the component type annotated with {@link Inject}.
    * <p/>
    * Summing it up, component retrieval happens in the following order:<br /> 1.  Look for a component that has already
    * been created and registered. 2.  Look for an appropriate component that exists in the {@link Configuration} that
    * may be injected from an external system. 3.  Look for a class definition passed in to the {@link Configuration} -
    * such as an EvictionPolicy implementation 4.  Attempt to create it by looking for an appropriate factory (annotated
    * with {@link DefaultFactoryFor})
    * <p/>
    *
    * @param componentClass type of component to be retrieved.  Should not be null.
    *
    * @return a fully wired component instance, or null if one cannot be found or constructed.
    *
    * @throws ConfigurationException if there is a problem with constructing or wiring the instance.
    */
   protected <T> T getOrCreateComponent(Class<T> componentClass) {
      return getOrCreateComponent(componentClass, componentClass.getName());
   }

   protected <T> T getOrCreateComponent(Class<T> componentClass, String name) {
      if (DEBUG_DEPENDENCIES) debugStack.push(name);

      T component = getComponent(componentClass, name);

      if (component == null) {
         // first see if this has been injected externally.
         component = getFromConfiguration(componentClass);
         boolean attemptedFactoryConstruction = false;

         if (component == null) {
            // create this component and add it to the registry
            AbstractComponentFactory factory = getFactory(componentClass);
            component = factory instanceof NamedComponentFactory ?
                    ((NamedComponentFactory) factory).construct(componentClass, name)
                    : factory.construct(componentClass);
            attemptedFactoryConstruction = true;

         }

         if (component != null) {
            registerComponent(component, componentClass);
         } else if (attemptedFactoryConstruction) {
            if (getLog().isTraceEnabled())
               getLog().trace("Registering a null for component %s", componentClass.getSimpleName());
            registerNullComponent(componentClass);
         }
      }

      if (DEBUG_DEPENDENCIES) debugStack.pop();
      return component;
   }

   /**
    * Retrieves a component factory instance capable of constructing components of a specified type.  If the factory
    * doesn't exist in the registry, one is created.
    *
    * @param componentClass type of component to construct
    *
    * @return component factory capable of constructing such components
    */
   protected AbstractComponentFactory getFactory(Class componentClass) {
      Map<Class, Class<? extends AbstractComponentFactory>> defaultFactoryMap = getDefaultFactoryMap();
      Class<? extends AbstractComponentFactory> cfClass = defaultFactoryMap.get(componentClass);
      if (cfClass == null)
         throw new ConfigurationException("No registered default factory for component '" + componentClass + "' found! Debug stack: " + debugStack);
      // a component factory is a component too!  See if one has been created and exists in the registry
      AbstractComponentFactory cf = getComponent(cfClass);
      if (cf == null) {
         // hasn't yet been created.  Create and put in registry
         cf = instantiateFactory(cfClass);
         if (cf == null)
            throw new ConfigurationException("Unable to locate component factory for component " + componentClass + "  Debug stack: " + debugStack);
         // we simply register this factory.  Registration will take care of constructing any dependencies.
         registerComponent(cf, cfClass);
      }

      // ensure the component factory is in the STARTED state!
      Component c = lookupComponent(cfClass, cfClass.getName());
      if (c.instance != cf)
         throw new ConfigurationException("Component factory " + cfClass + " incorrectly registered! Debug stack: " + debugStack);
      return cf;
   }

   protected Component lookupComponent(Class type, String componentName) {
      return componentLookup.get(componentName);
   }

   protected Map<Class, Class<? extends AbstractComponentFactory>> getDefaultFactoryMap() {
      if (defaultFactories == null) scanDefaultFactories();
      return defaultFactories;
   }

   /**
    * Scans the class path for classes annotated with {@link DefaultFactoryFor}, and analyses which components can be
    * created by such factories.
    */
   void scanDefaultFactories() {
      Map<Class, Class<? extends AbstractComponentFactory>> temp = new HashMap<Class, Class<? extends AbstractComponentFactory>>();
      Set<Class<? extends AbstractComponentFactory>> factories = getHardcodedFactories();

      for (Class<? extends AbstractComponentFactory> factory : factories) {
         // check if this implements auto-instantiable.  If it doesn't have a no-arg constructor throw an exception
         boolean factoryValid = true;
         try {
            if (AutoInstantiableFactory.class.isAssignableFrom(factory) && factory.getConstructor() == null) {
               factoryValid = false;
            }
         } catch (Exception e) {
            factoryValid = false;
         }

         if (!factoryValid)
            throw new RuntimeException("Factory class " + factory + " implements AutoInstantiableFactory but does not expose a public, no-arg constructor!  Debug stack: " + debugStack);

         DefaultFactoryFor dFFAnnotation = factory.getAnnotation(DefaultFactoryFor.class);
         if (dFFAnnotation != null) {
            for (Class targetClass : dFFAnnotation.classes()) temp.put(targetClass, factory);
         }
      }

      defaultFactories = temp;
   }

   /**
    * No such thing as a meta factory yet.  Factories are created using this method which attempts to use an empty
    * public constructor.
    *
    * @param factory class of factory to be created
    *
    * @return factory instance
    */
   AbstractComponentFactory instantiateFactory(Class<? extends AbstractComponentFactory> factory) {
      if (AutoInstantiableFactory.class.isAssignableFrom(factory)) {
         try {
            return factory.newInstance();
         }
         catch (Exception e) {
            // unable to get a hold of an instance!!
            throw new ConfigurationException("Unable to instantiate factory " + factory + "  Debug stack: " + debugStack, e);
         }
      } else {
         throw new ConfigurationException("Cannot auto-instantiate factory " + factory + " as it doesn't implement " + AutoInstantiableFactory.class.getSimpleName() + "!  Debug stack: " + debugStack);
      }
   }

   /**
    * registers a special "null" component that has no dependencies.
    *
    * @param type type of component to register as a null
    */
   void registerNullComponent(Class type) {
      registerComponent(NULL_COMPONENT, type);
   }

   /**
    * Retrieves a component from the {@link Configuration}
    *
    * @param componentClass component type
    *
    * @return component, or null if it cannot be found
    */
   @SuppressWarnings("unchecked")
   protected <T> T getFromConfiguration(Class<T> componentClass) {
      getLog().debug("Looking in configuration for an instance of %s that may have been injected from an external source.", componentClass);
      Method getter = BeanUtils.getterMethod(Configuration.class, componentClass);
      T returnValue = null;

      if (getter != null) {
         try {
            returnValue = (T) getter.invoke(getConfiguration());
         }
         catch (Exception e) {
            getLog().warn("Unable to invoke getter %s on Configuration.class!", e, getter);
         }
      }
      return returnValue;
   }

   /**
    * Retrieves the configuration component.
    *
    * @return a Configuration object
    */
   protected Configuration getConfiguration() {
      // this is assumed to always be present as a part of the bootstrap/construction of a ComponentRegistry.
      return getComponent(Configuration.class);
   }

   /**
    * Retrieves a component of a specified type from the registry, or null if it cannot be found.
    *
    * @param type type to find
    *
    * @return component, or null
    */
   public <T> T getComponent(Class<T> type) {
      return getComponent(type, type.getName());
   }

   @SuppressWarnings("unchecked")
   public <T> T getComponent(Class<T> type, String name) {
      Component wrapper = lookupComponent(type, name);
      if (wrapper == null) return null;

      return (T) (wrapper.instance == NULL_COMPONENT ? null : wrapper.instance);
   }

   /**
    * Registers the default class loader.  This method *must* be called before any other components are registered,
    * typically called by bootstrap code.  Defensively, it is called in the constructor of ComponentRegistry with a null
    * parameter.
    *
    * @param loader a class loader to use by default.  If this is null, the class loader used to load this instance of
    *               ComponentRegistry is used.
    */
   public void registerDefaultClassLoader(ClassLoader loader) {
      registerComponent(loader == null ? getClass().getClassLoader() : loader, ClassLoader.class);
      // make sure the class loader is non-volatile, so it survives restarts.
      componentLookup.get(ClassLoader.class.getName()).nonVolatile = true;
   }

   /**
    * Rewires components.  Used to rewire components in the CR if a cache has been stopped (moved to state TERMINATED),
    * which would (almost) empty the registry of components.  Rewiring will re-inject all dependencies so that the cache
    * can be started again.
    * <p/>
    */
   public void rewire() {
      // need to re-inject everything again.
      for (Component c : new HashSet<Component>(componentLookup.values())) {
         // inject dependencies for this component
         c.injectDependencies();
      }
   }

   /**
    * Scans each registered component for lifecycle methods, and adds them to the appropriate lists, and then sorts them
    * by priority.
    */
   private void populateLifecycleMethods() {
      for (Component c : componentLookup.values()) populateLifeCycleMethods(c);
   }

   private void populateLifeCycleMethods(Component c) {
      if (!c.methodsScanned) {
         c.methodsScanned = true;
         c.startMethods.clear();
         c.stopMethods.clear();

         List<Method> methods = getAllMethodsViaReflection(c.instance.getClass(), Start.class);
         for (Method m : methods) {
            PrioritizedMethod em = new PrioritizedMethod();
            em.component = c;
            em.method = m;
            em.priority = m.getAnnotation(Start.class).priority();
            c.startMethods.add(em);
         }

         methods = getAllMethodsViaReflection(c.instance.getClass(), Stop.class);
         for (Method m : methods) {
            PrioritizedMethod em = new PrioritizedMethod();
            em.component = c;
            em.method = m;
            em.priority = m.getAnnotation(Stop.class).priority();
            c.stopMethods.add(em);
         }
      }
   }

   /**
    * Removes any components not annotated as @NonVolatile.
    */
   public void resetNonVolatile() {
      // destroy all components to clean up resources
      for (Component c : new HashSet<Component>(componentLookup.values())) {
         // the component is volatile!!
         if (!c.nonVolatile) {
            componentLookup.remove(c.name);
         }
      }

      if (getLog().isTraceEnabled())
         getLog().trace("Reset volatile components.  Registry now contains %s", componentLookup.keySet());
   }

   // ------------------------------ START: Publicly available lifecycle methods -----------------------------
   //   These methods perform a check for appropriate transition and then delegate to similarly named internal methods.

   /**
    * This starts the components in the cache, connecting to channels, starting service threads, etc.  If the cache is
    * not in the {@link org.infinispan.lifecycle.ComponentStatus#INITIALIZING} state, it will be initialized first.
    */
   public void start() {

      if (!state.startAllowed()) {
         if (state.needToDestroyFailedCache())
            destroy(); // this will take us back to TERMINATED

         if (state.needToInitializeBeforeStart()) {
            state = ComponentStatus.INITIALIZING;
            rewire();
         } else
            return;
      }

      try {
         internalStart();
      }
      catch (Throwable t) {
         handleLifecycleTransitionFailure(t);
      }
   }

   /**
    * Stops the cache and sets the cache status to {@link org.infinispan.lifecycle.ComponentStatus#TERMINATED} once it
    * is done.  If the cache is not in the {@link org.infinispan.lifecycle.ComponentStatus#RUNNING} state, this is a
    * no-op.
    */
   public void stop() {
      if (!state.stopAllowed()) {
         return;
      }

      // Trying to stop() from FAILED is valid, but may not work
      boolean failed = state == ComponentStatus.FAILED;

      try {
         internalStop();
      }
      catch (Throwable t) {
         if (failed) {
            getLog().warn("Attempted to stop() from FAILED state, but caught exception; try calling destroy()", t);
         }
         failed = true;
         handleLifecycleTransitionFailure(t);
      }
      finally {
         if (!failed) state = ComponentStatus.TERMINATED;
      }
   }

   /**
    * Destroys the cache and frees up any resources.  Sets the cache status to {@link
    * org.infinispan.lifecycle.ComponentStatus#TERMINATED} when it is done.
    * <p/>
    * If the cache is in {@link org.infinispan.lifecycle.ComponentStatus#RUNNING} when this method is called, it will
    * first call {@link #stop()} to stop the cache.
    */
   private void destroy() {
      try {
         stop();
      }
      catch (CacheException e) {
         getLog().warn("Needed to call stop() before destroying but stop() threw exception. Proceeding to destroy", e);
      }

      try {
         resetNonVolatile();
      }
      finally {
         // We always progress to destroyed
         state = ComponentStatus.TERMINATED;
      }
   }
   // ------------------------------ END: Publicly available lifecycle methods -----------------------------

   // ------------------------------ START: Actual internal lifecycle methods --------------------------------

   /**
    * Sets the cacheStatus to FAILED and re-throws the problem as one of the declared types. Converts any
    * non-RuntimeException Exception to CacheException.
    *
    * @param t throwable thrown during failure
    */
   private void handleLifecycleTransitionFailure(Throwable t) {
      state = ComponentStatus.FAILED;
      if (t.getCause() != null && t.getCause() instanceof ConfigurationException)
         throw (ConfigurationException) t.getCause();
      else if (t.getCause() != null && t.getCause() instanceof InvocationTargetException && t.getCause().getCause() != null && t.getCause().getCause() instanceof ConfigurationException)
         throw (ConfigurationException) t.getCause().getCause();
      else if (t instanceof CacheException)
         throw (CacheException) t;
      else if (t instanceof RuntimeException)
         throw (RuntimeException) t;
      else if (t instanceof Error)
         throw (Error) t;
      else
         throw new CacheException(t);
   }

   private void internalStart() throws CacheException, IllegalArgumentException {
      // start all internal components
      // first cache all start, stop and destroy methods.
      populateLifecycleMethods();

      List<PrioritizedMethod> startMethods = new ArrayList<PrioritizedMethod>(componentLookup.size());
      for (Component c : componentLookup.values()) startMethods.addAll(c.startMethods);

      // sort the start methods by priority
      Collections.sort(startMethods);

      // fire all START methods according to priority


      for (PrioritizedMethod em : startMethods) em.invoke();

      addShutdownHook();

      getLog().info("Infinispan version: " + Version.printVersion());
      state = ComponentStatus.RUNNING;
   }

   protected void addShutdownHook() {
      // no op.  Override if needed.
   }

   protected void removeShutdownHook() {
      // no op.  Override if needed.
   }

   /**
    * Actual stop
    */
   private void internalStop() {
      state = ComponentStatus.STOPPING;
      removeShutdownHook();

      List<PrioritizedMethod> stopMethods = new ArrayList<PrioritizedMethod>(componentLookup.size());
      for (Component c : componentLookup.values()) stopMethods.addAll(c.stopMethods);

      Collections.sort(stopMethods);

      // fire all STOP methods according to priority
      for (PrioritizedMethod em : stopMethods) em.invoke();

      destroy();
   }

   // ------------------------------ END: Actual internal lifecycle methods --------------------------------

   /**
    * Asserts whether invocations are allowed on the cache or not.  Returns <tt>true</tt> if invocations are to be
    * allowed, <tt>false</tt> otherwise.  If the origin of the call is remote and the cache status is {@link
    * org.infinispan.lifecycle.ComponentStatus#INITIALIZING}, this method will block for up to {@link
    * Configuration#getStateRetrievalTimeout()} millis, checking for a valid state.
    *
    * @param originLocal true if the call originates locally (i.e., from the {@link org.infinispan.CacheDelegate} or
    *                    false if it originates remotely, i.e., from the {@link org.infinispan.remoting.InboundInvocationHandler}.
    *
    * @return true if invocations are allowed, false otherwise.
    */
   public boolean invocationsAllowed(boolean originLocal) {
      getLog().trace("Testing if invocations are allowed.");
      if (state.allowInvocations()) return true;

      // if this is a locally originating call and the cache is not in a valid state, return false.
      if (originLocal) return false;

      getLog().trace("Is remotely originating.");

      // else if this is a remote call and the status is STARTING, wait until the cache starts.
      if (state == ComponentStatus.INITIALIZING) {
         getLog().trace("Cache is initializing; block.");
         try {
            blockUntilCacheStarts();
            return true;
         }
         catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      } else {
         getLog().warn("Received a remote call but the cache is not in STARTED state - ignoring call.");
      }
      return false;
   }

   /**
    * Blocks until the current cache instance is in its {@link org.infinispan.lifecycle.ComponentStatus#RUNNING started}
    * phase. Blocks for up to {@link Configuration#getStateRetrievalTimeout()} milliseconds, throwing an
    * IllegalStateException if the cache doesn't reach this state even after this maximum wait time.
    *
    * @throws InterruptedException  if interrupted while waiting
    * @throws IllegalStateException if even after waiting the cache has not started.
    */
   private void blockUntilCacheStarts() throws InterruptedException, IllegalStateException {
      int pollFrequencyMS = 100;
      long startupWaitTime = getConfiguration().getStateRetrievalTimeout();
      long giveUpTime = System.currentTimeMillis() + startupWaitTime;

      while (System.currentTimeMillis() < giveUpTime) {
         if (state.allowInvocations()) break;
         Thread.sleep(pollFrequencyMS);
      }

      // check if we have started.
      if (!state.allowInvocations())
         throw new IllegalStateException("Cache not in STARTED state, even after waiting " + getConfiguration().getStateRetrievalTimeout() + " millis.");
   }

   /**
    * Returns an immutable set containing all the components that exists in the repository at this moment.
    *
    * @return a set of components
    */
   public Set<Component> getRegisteredComponents() {
      HashSet<Component> defensiveCopy = new HashSet<Component>(componentLookup.values());
      return Collections.unmodifiableSet(defensiveCopy);
   }

   @Override
   public AbstractComponentRegistry clone() throws CloneNotSupportedException {
      AbstractComponentRegistry dolly = (AbstractComponentRegistry) super.clone();
      dolly.state = ComponentStatus.INSTANTIATED;
      return dolly;
   }

   private List<Method> getAllMethodsViaReflection(Class c, Class<? extends Annotation> annotationType) {
      return reflectionCache.getAllMethods(c, annotationType);
   }

   /**
    * A wrapper representing a component in the registry
    */
   public class Component {
      /**
       * A reference to the object instance for this component.
       */
      Object instance;
      /**
       * The name of the component
       */
      String name;
      boolean methodsScanned;
      /**
       * List of injection methods used to inject dependencies into the component
       */
      List<Method> injectionMethods = new ArrayList<Method>(2);
      List<PrioritizedMethod> startMethods = new ArrayList<PrioritizedMethod>(2);
      List<PrioritizedMethod> stopMethods = new ArrayList<PrioritizedMethod>(2);
      /**
       * If true, then this component is not flushed before starting the ComponentRegistry.
       */
      boolean nonVolatile;

      @Override
      public String toString() {
         return "Component{" +
                 "instance=" + instance +
                 ", name=" + name +
                 ", nonVolatile=" + nonVolatile +
                 '}';
      }

      /**
       * Injects dependencies into this component.
       */
      public void injectDependencies() {
         for (Method m : injectionMethods) invokeInjectionMethod(instance, m);
      }

      public Object getInstance() {
         return instance;
      }

      public String getName() {
         return name;
      }
   }


   /**
    * Wrapper to encapsulate a method along with a priority
    */
   static class PrioritizedMethod implements Comparable<PrioritizedMethod> {
      Method method;
      Component component;
      int priority;

      public int compareTo(PrioritizedMethod o) {
         return (priority < o.priority ? -1 : (priority == o.priority ? 0 : 1));
      }


      @Override
      public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof PrioritizedMethod)) return false;

         PrioritizedMethod that = (PrioritizedMethod) o;

         if (priority != that.priority) return false;
         if (component != null ? !component.equals(that.component) : that.component != null) return false;
         if (method != null ? !method.equals(that.method) : that.method != null) return false;

         return true;
      }

      @Override
      public int hashCode() {
         int result = method != null ? method.hashCode() : 0;
         result = 31 * result + (component != null ? component.hashCode() : 0);
         result = 31 * result + priority;
         return result;
      }

      void invoke() {
         ReflectionUtil.invokeAccessibly(component.instance, method, null);
      }

      @Override
      public String toString() {
         return "PrioritizedMethod{" +
                 "method=" + method +
                 ", priority=" + priority +
                 '}';
      }
   }

}
