package io.tapdata.bigquery.service.bigQuery;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import io.tapdata.bigquery.service.stage.tapValue.TapValueForBigQuery;
import io.tapdata.bigquery.service.stage.tapValue.ValueHandel;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.type.*;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.entity.WriteListResult;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.tapdata.base.ConnectorBase.list;

public class WriteRecord extends BigQueryStart{
    private static final String TAG = WriteRecord.class.getSimpleName();
    ValueHandel valueHandel;

    public WriteRecord(TapConnectionContext connectorContext){
        super(connectorContext);
    }
    public static WriteRecord create(TapConnectionContext connectorContext){
        return new WriteRecord(connectorContext);
    }

    public static WriteRecord create(TapConnectionContext connectorContext,ValueHandel valueHandel){
        return new WriteRecord(connectorContext).valueHandel(valueHandel);
    }

    public String fullSqlTable(String tableId){
        return "`"+this.config.projectId() + "`.`" + this.config.tableSet() + "`.`"+tableId+"`";
    }

    public WriteRecord valueHandel(ValueHandel valueHandel){
        this.valueHandel = valueHandel;
        return this;
    }
    public ValueHandel valueHandel(){
        return this.valueHandel;
    }

    public final String delimiter = ",";
    public final String equals = "=";
    public final String tab = " ";
    public final String empty = "";

    private final AtomicBoolean running = new AtomicBoolean(true);
    public void onDestroy() {
        this.running.set(false);
    }

    /**
     * @deprecated
     * */
    public synchronized void write(List<TapRecordEvent> tapRecordEvents, TapTable tapTable, Consumer<WriteListResult<TapRecordEvent>> writeListResultConsumer){
        WriteListResult<TapRecordEvent> writeListResult = new WriteListResult<>(0L, 0L, 0L, new HashMap<>());
        TapRecordEvent errorRecord = null;
        try {
            for (TapRecordEvent tapRecordEvent : tapRecordEvents) {
                if (!running.get()) break;
                try {
                    String sql = null;
                    if (! ( tapRecordEvent instanceof TapDeleteRecordEvent )){
                        Map<String, Object> after =
                                tapRecordEvent instanceof TapInsertRecordEvent ?
                                        ((TapInsertRecordEvent) tapRecordEvent).getAfter()
                                        : ( tapRecordEvent instanceof TapUpdateRecordEvent ? ((TapUpdateRecordEvent) tapRecordEvent).getAfter(): null);
                        if (null == after){
                            writeListResult.addError(tapRecordEvent, new Exception("Event type \"" + tapRecordEvent.getClass().getSimpleName() + "\" not support: " + tapRecordEvent));
                            continue;
                        }
                        Boolean aBoolean = hasRecord(sqlMarker, tapTable,tapRecordEvent);
                        if (null == aBoolean) continue;
                        String[] sqls = aBoolean ? updateSql(list(after),tapTable,tapRecordEvent) : insertSql(list(after),tapTable);
                        sql = null!=sqls?sqls[0]:null;
                    }else{
                        sql = delSql(tapTable,tapRecordEvent);
                    }
                    BigQueryResult bigQueryResult = sqlMarker.executeOnce(sql);
                    long totalRows = bigQueryResult.getTotalRows();
                    totalRows = totalRows>0?totalRows:0;
                    if (tapRecordEvent instanceof TapInsertRecordEvent){
                        writeListResult.incrementInserted(totalRows);
                    }else if(tapRecordEvent instanceof TapUpdateRecordEvent){
                        writeListResult.incrementModified(totalRows);
                    }else if(tapRecordEvent instanceof TapDeleteRecordEvent){
                        writeListResult.incrementRemove(totalRows);
                    }
                }catch (Throwable e) {
                    errorRecord = tapRecordEvent;
                    throw e;
                }
            }
        }catch (Throwable e) {
            writeListResult.setInsertedCount(0);
            writeListResult.setModifiedCount(0);
            writeListResult.setRemovedCount(0);
            if (null != errorRecord) writeListResult.addError(errorRecord, e);
            throw e;
        }finally {
            writeListResultConsumer.accept(writeListResult);
        }
    }

    public synchronized void writeV2(List<TapRecordEvent> tapRecordEvents, TapTable tapTable, Consumer<WriteListResult<TapRecordEvent>> writeListResultConsumer){
        SqlMarker sqlMarker = SqlMarker.create(this.config.serviceAccount());
        WriteListResult<TapRecordEvent> writeListResult = new WriteListResult<>(0L, 0L, 0L, new HashMap<>());
        TapRecordEvent errorRecord = null;
        try {
            for (TapRecordEvent tapRecordEvent : tapRecordEvents) {
                if (!running.get()) break;
                try {
                    if (tapRecordEvent instanceof TapInsertRecordEvent){
                        String sql = this.insertIfExitsUpdate(
                                ((TapInsertRecordEvent)tapRecordEvent).getAfter()
                                ,tapTable
                                ,tapRecordEvent );
                        writeListResult.incrementInserted(this.executeSql(sqlMarker,sql));
                    } else if (tapRecordEvent instanceof TapUpdateRecordEvent){
                        String sql = this.insertIfExitsUpdate(
                                ((TapUpdateRecordEvent)tapRecordEvent).getAfter()
                                ,tapTable
                                ,tapRecordEvent );
                        writeListResult.incrementModified(this.executeSql(sqlMarker,sql));
                    } else if (tapRecordEvent instanceof TapDeleteRecordEvent){
                        String sql = this.delSql(tapTable,tapRecordEvent);
                        writeListResult.incrementRemove(this.executeSql(sqlMarker,sql));
                    } else {
                        writeListResult.addError(tapRecordEvent, new Exception("Event type \"" + tapRecordEvent.getClass().getSimpleName() + "\" not support: " + tapRecordEvent));
                    }
                }catch (Throwable e) {
                    errorRecord = tapRecordEvent;
                    throw e;
                }
            }
            writeListResultConsumer.accept(writeListResult);
        }catch (Throwable e) {
            writeListResult.setInsertedCount(0);
            writeListResult.setModifiedCount(0);
            writeListResult.setRemovedCount(0);
            writeListResultConsumer.accept(writeListResult);
            if (null != errorRecord) writeListResult.addError(errorRecord, e);
            throw e;
        }
    }

    private long executeSql(SqlMarker sqlMarker,String sql){
        BigQueryResult bigQueryResult = sqlMarker.executeOnce(sql);
        long totalRows = bigQueryResult.getTotalRows();
        return totalRows>0?totalRows:0L;
    }

    /**
     * @deprecated
     * */
    public Boolean hasRecord(SqlMarker sqlMarker,Map<String,Object> record,TapTable tapTable){
        String selectSql = selectSql(record, tapTable);
        if (null == selectSql) return null;
        if (this.empty.equals(selectSql)){
            return false;
        }
        BigQueryResult tableResult = sqlMarker.executeOnce(selectSql);
        return null != tableResult && tableResult.getTotalRows()>0;
    }

    public Boolean hasRecord(SqlMarker sqlMarker,TapTable tapTable,TapRecordEvent event){
        String selectSql = this.selectSql(tapTable,event);
        if (null == selectSql) return null;
        if (this.empty.equals(selectSql)){
            return false;
        }
        BigQueryResult tableResult = sqlMarker.executeOnce(selectSql);
        return null != tableResult && tableResult.getTotalRows()>0;
    }

    /**
     * @deprecated
     * */
    public String delSql(List<Map<String,Object>> record,TapTable tapTable){
        StringBuilder sql = new StringBuilder(" DELETE FROM ");
        sql.append(this.fullSqlTable(tapTable.getId())).append(" WHERE 1=2 ");
        Map<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null != nameFieldMap && !nameFieldMap.isEmpty() && null != record && !record.isEmpty()){
            for (Map<String, Object> map : record) {
                sql.append( " OR ( " );
                StringJoiner whereSql = new StringJoiner(" ADN ");
                for (Map.Entry<String,TapField> key : nameFieldMap.entrySet()) {
                    if (key.getValue().getPrimaryKey()) {
                        whereSql.add(key.getKey()+this.equals+sqlValue(map.get(key.getKey()),key.getValue()));
                    }
                }
                sql.append(whereSql.toString()).append(" ) ");
            }
            return sql.toString();
        }
        return null;
    }

    public String delSql(TapTable tapTable,TapRecordEvent event){
        return this.delBatchSql(tapTable, list(event));
    }

    public String delBatchSql(TapTable tapTable,List<TapRecordEvent> event){
        StringBuilder sql = new StringBuilder(" DELETE FROM ");
        sql.append(this.fullSqlTable(tapTable.getId())).append(" WHERE 1=2 ");
        Map<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()){
            String finalSql = sql.toString();
            TapLogger.debug(TAG,"Not any fields on tap table,sql must not delete any data,sql is [{}]",finalSql);
            return finalSql;
        }
        event.forEach(eve->{
            Map<String, Object> filter = eve.getFilter(tapTable.primaryKeys(true));
            if (null == filter || filter.isEmpty()) {
                String error = String.format("A tapEvent can not filter primary keys,event = %s",eve);
                TapLogger.debug(TAG,error);
                return;
            }
            int filterSize = filter.size();
            sql.append( " OR " );
            if (filterSize>1) sql.append("( ");
            StringJoiner whereSql = new StringJoiner(" ADN ");
            filter.forEach((primaryKey,value)-> whereSql.add(primaryKey+this.equals+sqlValue(value,nameFieldMap.get(primaryKey))));
            sql.append(whereSql.toString());
            if (filterSize>1) sql.append(" ) ");
        });
        return sql.toString();//.replaceAll("1=2  OR",this.empty);
    }

    /**
     * @deprecated please use the method name is insertIfExitsUpdate
     * */
    public String[] updateSql(List<Map<String,Object>> record,TapTable tapTable,TapRecordEvent event){
        Map<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()) return null;
        Map<String, Object> filter = event.getFilter(tapTable.primaryKeys(true));
        if (null == filter || filter.isEmpty()) return insertSql(record, tapTable);

        if (null == record || record.isEmpty()) return null;
        int size = record.size();
        String []sql = new String[size];
        for (int i = 0; i < size ; i++) {
            Map<String ,Object> recordItem = record.get(i);
            if (null == recordItem ){
                sql[i] = null;
                continue;
            }
            StringBuilder sqlBuilder = new StringBuilder(" UPDATE ");
            sqlBuilder.append(this.fullSqlTable(tapTable.getId())).append(" SET ");
            StringBuilder whereBuilder = new StringBuilder(" WHERE ");
            filter.forEach((key,value)->whereBuilder.append("`").append(key).append("` = ").append(sqlValue(value,nameFieldMap.get(key))).append(" AND "));
            for (Map.Entry<String,TapField> field : nameFieldMap.entrySet()) {
                if (null == field) continue;
                String fieldName = field.getKey();
                Object value = recordItem.get(fieldName);
                if (null==value) continue;
                String sqlValue = sqlValue(value, field.getValue());
                if (!field.getValue().getPrimaryKey()){
                    sqlBuilder.append("`").append(fieldName).append("` = ").append(sqlValue).append(" , ");
                }else {
                    whereBuilder.append("`").append(fieldName).append("` = ").append(sqlValue).append(" AND ");
                }
            }
            sqlBuilder.append("@").append(whereBuilder.append("@"));
            sql[i] = sqlBuilder.toString().replaceAll(", @",this.empty).replaceAll("AND @",this.empty);
        }
        return sql;
    }

    /**
     * @deprecated please use the method name is insertIfExitsUpdate
     * */
    public String[] insertSql(List<Map<String,Object>> record,TapTable tapTable){
        if (null == record || record.isEmpty()) return null;
        Map<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()) return null;
        int size = record.size();
        String []sql = new String[size];
        for (int i = 0; i < size ; i++) {
            Map<String ,Object> recordItem = record.get(i);
            if (null == recordItem ){
                sql[i] = null;
                continue;
            }
            StringBuilder sqlBuilder = new StringBuilder(" INSERT INTO ");
            sqlBuilder.append(this.fullSqlTable(tapTable.getId()));
            StringBuilder keyBuilder = new StringBuilder();
            StringBuilder valuesBuilder = new StringBuilder();

            for (Map.Entry<String, TapField> field : nameFieldMap.entrySet()) {
                if (null == field) continue;
                String fieldName = field.getKey();
                if (null == fieldName || this.empty.equals(fieldName)) continue;

                //@TODO 对不同值处理
                Object value = recordItem.get(fieldName);
                if (null != value ) {
                    keyBuilder.append("`").append(fieldName.replaceAll("\\.","_")).append("` , ");
                    valuesBuilder.append(sqlValue(value,field.getValue())).append(" , ");
                }
            }
            keyBuilder.append("@");
            valuesBuilder.append("@");
            sqlBuilder.append(" ( ").append(keyBuilder.toString().replaceAll(", @",this.empty)).append(" ) ").append(" VALUES ( ").append(valuesBuilder.toString().replaceAll(", @",this.empty)).append(" ) ");
            sql[i] = sqlBuilder.toString();
        }
        return sql;
    }

    /**
     * @deprecated please use the method name is insertIfExitsUpdate
     * */
    public String selectSql(Map<String,Object> record,TapTable tapTable){
        if (null == record || null == tapTable) return null;
        LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()) return this.empty;
        StringBuilder sql = new StringBuilder(" SELECT * FROM ").append(this.fullSqlTable(tapTable.getId())).append(" WHERE 1=1 ");
        for (Map.Entry<String,TapField> key : nameFieldMap.entrySet()) {
            if (null == key) continue;
            if (key.getValue().getPrimaryKey()) {
                sql.append(" AND `").append(key.getKey()).append("` = ").append(sqlValue(record.get(key.getKey()),key.getValue())).append(this.tab);
            }
        }
        return sql.toString().replaceAll("1=1  AND",this.empty);
    }

    /**
     * @deprecated please use the method name is insertIfExitsUpdate
     * */
    public String selectSql(TapTable tapTable,TapRecordEvent event){
        LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()) return this.empty;
        StringBuilder sql = new StringBuilder(" SELECT * FROM ").append(this.fullSqlTable(tapTable.getId())).append(" WHERE 1=1 ");
        Map<String, Object> filter = event.getFilter(tapTable.primaryKeys(true));
        if (null == filter || filter.isEmpty()) return null;
        filter.forEach((key,value)->sql.append(" AND `").append(key).append("` = ").append(sqlValue(value,nameFieldMap.get(key))).append(this.tab));
        return sql.toString().replaceAll("1=1  AND",this.empty);
    }

    /**
     * DECLARE exits INT64;
     * SET exits = (select 1 from `SchemaoOfJoinSet.JoinTestSchema` where _id = '2');
     * if exits = 1 then
     *   update `SchemaoOfJoinSet.JoinTestSchema` set name = 'update-test' where _id = '2';
     * else
     *   insert into `SchemaoOfJoinSet.JoinTestSchema` (_id,name) values ('2','test-insert');
     * end if
     * */
    public String insertIfExitsUpdate(Map<String,Object> record,TapTable tapTable,TapRecordEvent event){
        String whereSql = this.whereSql(tapTable,event);
        LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()) return this.empty;
        if (null == record || record.isEmpty()) return this.empty;
        StringJoiner keyInsertSql = new StringJoiner(this.delimiter);
        StringJoiner valueInsertSql = new StringJoiner(this.delimiter);
        StringJoiner subUpdateSql = new StringJoiner(this.delimiter);
        //FieldChecker.verifyFieldName(nameFieldMap);
        nameFieldMap.forEach((key,field)->{
            String value = this.sqlValue(record.get(key),field);
            keyInsertSql.add("`"+key+"`");
            valueInsertSql.add(value);
            subUpdateSql.add("`"+key+"`"+this.equals+value);
        });
        String table = this.fullSqlTable(tapTable.getId());
        StringBuilder insertIfExitsUpdateSql = new StringBuilder("DECLARE exits INT64;");
        insertIfExitsUpdateSql.append("SET exits = (select 1 from ")
                .append(table)
                .append(whereSql).append(" ); ")
                .append(" if exits = 1 then ")
                .append("  update ")
                .append(table)
                .append(" SET ").append(subUpdateSql).append(this.tab)
                .append(whereSql).append(" ;")
                .append(" ELSE ")
                .append("  insert into ")
                .append(table)
                .append(" (").append(keyInsertSql.toString()).append(" ) VALUES ( ").append(valueInsertSql.toString()).append(" ); ")
                .append(" END IF ");
        return insertIfExitsUpdateSql.toString();
    }

    /**
     * JSON :  INSERT INTO mydataset.table1 VALUES(1, JSON '{"name": "Alice", "age": 30}');
     *
     * */
    public String sqlValue(Object value,TapField field){
        TapType tapType = field.getTapType();
        //return TapValueForBigQuery.sqlValue(value,tapType.getClass());
        if (null == this.valueHandel){
            BeanUtil.copyProperties(ValueHandel.create(),this.valueHandel,false);
        }
        return this.valueHandel.sqlValue(value,null == tapType? null:tapType.getClass());

//        if (null == value) return "NULL";
//        TapType tapType = field.getTapType();
//        if (tapType instanceof TapString){
//            return "'"+String.valueOf(value).replaceAll("'","\\'")+"'";
//        }else if(tapType instanceof TapNumber){
//            return this.empty+value;
//        }else if(tapType instanceof TapBoolean){
//            return this.empty+value;
//        }else if(tapType instanceof TapMap){
//            return jsonValue(value);
//        }else if(tapType instanceof TapBinary){
//            return " FROM_BASE64('"+Base64.encode(String.valueOf(value)) +"') ";
//        }else if(tapType instanceof TapArray){
//            return jsonValue(value);
//        }else if(tapType instanceof TapDate){
//            return "'"+value+"'";
//        }else if(tapType instanceof TapYear){
//            return "'"+value+"'";
//        }else if(tapType instanceof TapTime){
//            return "'"+value+"'";
//        }else if(tapType instanceof TapDateTime){
//            return "'"+value+"'";
//        }else{
//            return ""+value;
//        }
    }

    private String jsonValue(Object obj){
        String value = String.valueOf(obj);
        return " JSON '" +
                JSONUtil.toJsonPrettyStr(value)
                        .replaceAll("'","\\'")
                        .replaceAll("\"\\{","\\{")
                        .replaceAll("}\"","}")
                        .replaceAll("\n","")
                + "'";
    }

    /**
     *
     * (key1,key2,key3,...) VALUES (value1,value2,value3,...)
     * */
    private String subInsertSql(Map<String,Object> record,TapTable tapTable){
        LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null == nameFieldMap || nameFieldMap.isEmpty()) return this.empty;
        if (null == record || record.isEmpty()) return this.empty;
        StringJoiner keySql = new StringJoiner(delimiter);
        StringJoiner valueSql = new StringJoiner(delimiter);
        nameFieldMap.forEach((key,value)->{
            keySql.add("`"+key.replaceAll("\\.","_")+"`");
            valueSql.add(this.sqlValue(record.get(key),value));
        });
        if (keySql.length()>0 && valueSql.length()>0){
            return " (" + keySql.toString() + " ) VALUES ( " + valueSql.toString() + " ) ";
        }else {
            TapLogger.info(TAG,"A insert sql error ,keys or values can not be find. keys = {},values = {}",keySql.toString(),valueSql.toString());
            return this.empty;
        }
    }

    /**
     * SET key1=value1,key2=value2,...
     * */
    private String subUpdateSql(Map<String,Object> record,TapTable tapTable){
        LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null==nameFieldMap || nameFieldMap.isEmpty()) return this.empty;
        if (null == record || record.isEmpty()) return this.empty;
        StringBuilder subSql = new StringBuilder(" SET ");
        nameFieldMap.forEach((key,field)->
                subSql.append(this.tab)
                    .append(key)
                    .append(equals)
                    .append(this.sqlValue(record.get(key),field))
                    .append(delimiter)
                    .append(this.tab)
        );
        if (subSql.length()<=0){
            TapLogger.debug(TAG,"A update sql error, not key-value for subSql,record = {}",record);
        }
        return subSql.toString();
    }
    /**
     * WHERE xxx = xxx ADN xxx = xxx ......
     * */
    private String whereSql(TapTable tapTable,TapRecordEvent event){
        LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
        if (null==nameFieldMap || nameFieldMap.isEmpty()) return this.empty;
        Map<String, Object> filter = event.getFilter(tapTable.primaryKeys(true));
        if (null == filter || filter.isEmpty()) return this.empty;
        StringJoiner whereSql = new StringJoiner(" ADN ");
        filter.forEach((primaryKey,value)-> whereSql.add(primaryKey+this.equals+sqlValue(value,nameFieldMap.get(primaryKey))));
        return whereSql.length()>0?" WHERE "+whereSql.toString():this.empty;
    }
}
