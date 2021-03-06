package org.infinispan.atomic;

import org.infinispan.Cache;
import static org.infinispan.atomic.AtomicHashMapTestAssertions.assertIsEmpty;
import static org.infinispan.atomic.AtomicHashMapTestAssertions.assertIsEmptyMap;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.CacheContainer;
import org.infinispan.test.AbstractInfinispanTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

@Test(groups = "functional", testName = "atomic.AtomicMapLocalTest")
public class AtomicMapLocalTest extends AbstractInfinispanTest {

   Cache<String, Object> cache;
   TransactionManager tm;
   private CacheContainer cacheContainer;

   @BeforeClass
   public void setUp() {
      ConfigurationBuilder c = new ConfigurationBuilder();
      c.invocationBatching().enable();
      cacheContainer = TestCacheManagerFactory.createCacheManager(c);
      cache = cacheContainer.getCache();
      tm = TestingUtil.getTransactionManager(cache);
   }

   @AfterClass
   public void tearDown() {
      TestingUtil.killCacheManagers(cacheContainer);
      cache =null;
      tm = null;
   }

   @AfterMethod
   public void clearUp() throws SystemException {
      if (tm.getTransaction() != null) {
         try {
            tm.rollback();
         } catch (Exception ignored) {
            // try to suspend?
            tm.suspend();
         }
      }
      cache.clear();
   }

   public void testAtomicMap() {
      AtomicMap<String, String> map = AtomicMapLookup.getAtomicMap(cache, "map");

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");
      assert cache.containsKey("map");

      map.put("blah", "blah");
      assert map.size() == 1;
      assert map.get("blah").equals("blah");
      assert map.containsKey("blah");

      map.clear();

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");
      assert cache.containsKey("map");

      AtomicMapLookup.removeAtomicMap(cache, "map");
      assert !cache.containsKey("map");
   }

   public void testReadSafetyEmptyCache() throws Exception {
      AtomicMap<String, String> map = AtomicMapLookup.getAtomicMap(cache, "map");

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");

      tm.begin();
      map.put("blah", "blah");
      assert map.size() == 1;
      assert map.get("blah").equals("blah");
      assert map.containsKey("blah");
      Transaction t = tm.suspend();

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");

      tm.resume(t);
      tm.commit();

      assert map.size() == 1;
      assert map.get("blah").equals("blah");
      assert map.containsKey("blah");

      map.clear();

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");
   }

   public void testReadSafetyNotEmptyCache() throws Exception {
      AtomicMap<String, String> map = AtomicMapLookup.getAtomicMap(cache, "map");

      tm.begin();
      map.put("blah", "blah");
      assert map.get("blah").equals("blah");

      Transaction t = tm.suspend();
      assert map.isEmpty();
      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");

      tm.resume(t);
      tm.commit();

      assert map.size() == 1;
      assert map.get("blah").equals("blah");
      assert map.containsKey("blah");

      map.clear();

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");
   }

   public void testReadSafetyRollback() throws Exception {
      AtomicMap<String, String> map = AtomicMapLookup.getAtomicMap(cache, "map");

      tm.begin();
      map.put("blah", "blah");
      assert map.size() == 1;
      assert map.get("blah").equals("blah");
      assert map.containsKey("blah");
      Transaction t = tm.suspend();

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");

      tm.resume(t);
      tm.rollback();

      assertIsEmpty(map);
      assertIsEmptyMap(cache, "map");
   }

}
