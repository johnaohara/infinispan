package org.infinispan.persistence.jdbc.table.management;

import org.infinispan.persistence.jdbc.configuration.TableManipulationConfiguration;
import org.infinispan.persistence.jdbc.connectionfactory.ConnectionFactory;
import org.infinispan.persistence.jdbc.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Set;

/**
 * @author Ryan Emerson
 */
class SybaseTableManager extends AbstractTableManager {
   private static final Log LOG = LogFactory.getLog(SybaseTableManager.class, Log.class);

   SybaseTableManager(ConnectionFactory connectionFactory, TableManipulationConfiguration config, DbMetaData metaData) {
      super(connectionFactory, config, metaData, LOG);
   }

   @Override
   public String getUpdateRowSql() {
      if (updateRowSql == null) {
         updateRowSql = String.format("UPDATE %s SET %s = ? , %s = ? WHERE %s = convert(%s,?)",
                                      getTableName(), config.dataColumnName(), config.timestampColumnName(),
                                      config.idColumnName(), config.idColumnType());
      }
      return updateRowSql;
   }

   @Override
   public String getSelectRowSql() {
      if (selectRowSql == null) {
         selectRowSql = String.format("SELECT %s, %s, FROM %s WHERE %s = convert(%s,?)",
                                      config.idColumnName(), config.dataColumnName(), getTableName(),
                                      config.idColumnName(), config.idColumnType());
      }
      return selectRowSql;
   }

   @Override
   public String getSelectMultipleRowSql(int numberOfParams) {
      String selectCriteria = config.idColumnName() + " = convert(" + config.idColumnType() + ",?)";
      return getSelectMultipleRowSql(numberOfParams, selectCriteria);
   }

   @Override
   public String getSelectIdRowSql() {
      if (selectIdRowSql == null) {
         selectIdRowSql = String.format("SELECT %s FROM %s WHERE %s = convert(%s,?)",
                                        config.idColumnName(), getTableName(), config.idColumnName(), config.idColumnType());
      }
      return selectIdRowSql;
   }

   @Override
   public String getDeleteRowSql() {
      if (deleteRowSql == null) {
         deleteRowSql = String.format("DELETE FROM %s WHERE %s = convert(%s,?)",
                                      getTableName(), config.idColumnName(), config.idColumnType());
      }
      return deleteRowSql;
   }
}