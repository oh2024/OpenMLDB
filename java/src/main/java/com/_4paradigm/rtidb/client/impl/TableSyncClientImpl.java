package com._4paradigm.rtidb.client.impl;

import com._4paradigm.rtidb.client.KvIterator;
import com._4paradigm.rtidb.client.ScanOption;
import com._4paradigm.rtidb.client.TableSyncClient;
import com._4paradigm.rtidb.client.TabletException;
import com._4paradigm.rtidb.client.ha.PartitionHandler;
import com._4paradigm.rtidb.client.ha.RTIDBClient;
import com._4paradigm.rtidb.client.ha.RTIDBClientConfig;
import com._4paradigm.rtidb.client.ha.TableHandler;
import com._4paradigm.rtidb.client.schema.*;
import com._4paradigm.rtidb.ns.NS;
import com._4paradigm.rtidb.tablet.Tablet;
import com._4paradigm.rtidb.utils.Compress;
import com.google.common.base.Charsets;
import com.google.protobuf.ByteBufferNoCopy;
import com.google.protobuf.ByteString;
import rtidb.api.TabletServer;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class TableSyncClientImpl implements TableSyncClient {
    private RTIDBClient client;

    public TableSyncClientImpl(RTIDBClient client) {
        this.client = client;
    }

    @Override
    public KvIterator scan(String tname, Map<String, Object> keyMap, long st, long et, ScanOption option) throws TimeoutException, TabletException {
        if (option.getIdxName() == null) throw new TabletException("idx name is required ");
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(option.getIdxName());
        if (list == null) {
            throw new TabletException("no index name in table" + option.getIdxName());
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyMap, list, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, combinedKey, option.getIdxName(), st,
                et, option.getTsName(), option.getLimit(), option.getAtLeast(), th);
    }

    @Override
    public KvIterator scan(String tname, String key, long st, long et, ScanOption option) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, key, option.getIdxName(), st,
                et, option.getTsName(), option.getLimit(), option.getAtLeast(), th);
    }

    @Override
    public KvIterator scan(String tname, Object[] keyArr, long st, long et, ScanOption option) throws TimeoutException, TabletException {
        if (option.getIdxName() == null) throw new TabletException("idx name is required ");
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(option.getIdxName());
        if (list == null) {
            throw new TabletException("no index name in table" + option.getIdxName());
        }
        if (keyArr.length != list.size()) {
            throw new TabletException("check key number failed");
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyArr, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, combinedKey, option.getIdxName(), st,
                et, option.getTsName(), option.getLimit(), option.getAtLeast(), th);
    }

    @Override
    public boolean put(int tid, int pid, String key, long time, byte[] bytes) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("fail to find table with id " + tid);
        }
        if (th.getPartitions().length <= pid) {
            throw new TabletException("fail to find partition with pid " + pid + " from table " + th.getTableInfo().getName());
        }
        PartitionHandler ph = th.getHandler(pid);
        return put(tid, pid, key, time, bytes, th);
    }

    @Override
    public boolean put(int tid, int pid, String key, long time, String value) throws TimeoutException, TabletException {
        return put(tid, pid, key, time, value.getBytes(Charsets.UTF_8));
    }

    @Override
    public boolean put(String tname, String key, long time, String value) throws TimeoutException, TabletException {
        return put(tname, key, time, value.getBytes(Charsets.UTF_8));
    }

    @Override
    public boolean put(int tid, int pid, long time, Object[] row) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("fail to find table with id " + tid);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        ByteBuffer buffer = null;
        if (row.length == th.getSchema().size()) {
            buffer = RowCodec.encode(row, th.getSchema());
        } else {
            List<ColumnDesc> columnDescs = th.getSchemaMap().get(row.length);
            if (columnDescs == null) {
                throw new TabletException("no schema for column count " + row.length);
            }
            int modifyTimes = row.length - th.getSchema().size();
            if (row.length > th.getSchema().size() + th.getSchemaMap().size()) {
                modifyTimes = th.getSchemaMap().size();
            }
            buffer = RowCodec.encode(row, columnDescs, modifyTimes);
        }
        List<Tablet.Dimension> dimList = TableClientCommon.fillTabletDimension(row, th, client.getConfig().isHandleNull());
        return put(tid, pid, null, time, dimList, null, buffer, th);
    }

    @Override
    public ByteString get(int tid, int pid, String key) throws TimeoutException, TabletException {
        return get(tid, pid, key, 0l);
    }

    @Override
    public ByteString get(int tid, int pid, String key, long time) throws TimeoutException, TabletException {
        return get(tid, pid, key, null, time, null, null, client.getHandler(tid), 0l, null);
    }

    @Override
    public Object[] getRow(int tid, int pid, String key, long time) throws TimeoutException, TabletException {
        return getRow(tid, pid, key, null, time);
    }

    @Override
    public Object[] getRow(int tid, int pid, String key, String idxName, long time) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("fail to find table with id " + tid);
        }
        ByteString response = get(tid, pid, key, idxName, time, null, null, th, 0l, null);
        if (response == null || response.isEmpty()) {
            return null;
        }
        Object[] row = null;
        if (th.getSchemaMap().size() > 0) {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema(), th.getSchemaMap().size());
        } else {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema());
        }
        return row;
    }

    @Override
    public Object[] getRow(String tname, Object[] keyArr, String idxName, long time, String tsName, Tablet.GetType type) throws TimeoutException, TabletException {
        return getRow(tname, keyArr, idxName, time, tsName, type, 0l, null);
    }

    @Override
    public Object[] getRow(String tname, Map<String, Object> keyMap, String idxName, long time, String tsName, Tablet.GetType type) throws TimeoutException, TabletException {
        return getRow(tname, keyMap, idxName, time, tsName, type, 0l, null);
    }

    @Override
    public Object[] getRow(int tid, int pid, String key, String idxName) throws TimeoutException, TabletException {
        return getRow(tid, pid, key, idxName, 0);
    }

    @Override
    public ByteString get(String tname, String key) throws TimeoutException, TabletException {
        return get(tname, key, 0l);
    }

    @Override
    public Object[] getRow(String tname, String key, long time) throws TimeoutException, TabletException {
        return getRow(tname, key, null, time, null);
    }

    @Override
    public Object[] getRow(String tname, String key, String idxName) throws TimeoutException, TabletException {
        return getRow(tname, key, idxName, 0);
    }

    @Override
    public Object[] getRow(String tname, String key, String idxName, long time) throws TimeoutException, TabletException {
        return getRow(tname, key, idxName, time, null, null);
    }

    @Override
    public Object[] getRow(String tname, String key, String idxName, long time, Tablet.GetType type) throws TimeoutException, TabletException {
        return getRow(tname, key, idxName, time, null, type);
    }

    @Override
    public Object[] getRow(String tname, String key, String idxName, long time, String tsName, Tablet.GetType type) throws TimeoutException, TabletException {
        return getRow(tname, key, idxName, time, tsName, type, 0l, null);
    }

    @Override
    public Object[] getRow(String tname, String key, String idxName, long time, String tsName, Tablet.GetType type,
                           long et, Tablet.GetType etType) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        ByteString response = get(th.getTableInfo().getTid(), pid, key, idxName, time, tsName, type, th, et, etType);
        if (response == null || response.isEmpty()) {
            return null;
        }
        Object[] row = null;
        if (th.getSchemaMap().size() > 0) {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema(), th.getSchemaMap().size());
        } else {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema());
        }
        return row;
    }

    @Override
    public Object[] getRow(String tname, Object[] keyArr, String idxName, long time, String tsName, Tablet.GetType type,
                           long et, Tablet.GetType etType) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        if (keyArr.length != list.size()) {
            throw new TabletException("check key number failed");
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyArr, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        ByteString response = get(th.getTableInfo().getTid(), pid, combinedKey, idxName, time, tsName, type, th, et, etType);
        if (response == null || response.isEmpty()) {
            return null;
        }
        Object[] row = null;
        if (th.getSchemaMap().size() > 0) {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema(), th.getSchemaMap().size());
        } else {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema());
        }
        return row;
    }

    @Override
    public Object[] getRow(String tname, Map<String, Object> keyMap, String idxName, long time, String tsName,
                           Tablet.GetType type, long et, Tablet.GetType etType) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyMap, list, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        ByteString response = get(th.getTableInfo().getTid(), pid, combinedKey, idxName, time, tsName, type, th, et, etType);
        if (response == null || response.isEmpty()) {
            return null;
        }
        Object[] row = null;
        if (th.getSchemaMap().size() > 0) {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema(), th.getSchemaMap().size());
        } else {
            row = RowCodec.decode(response.asReadOnlyByteBuffer(), th.getSchema());
        }
        return row;
    }

    @Override
    public RelationalIterator traverse(String tableName, ReadOption ro) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tableName);
        if (th == null) {
            throw new TabletException("no table with name " + tableName);
        }
        Set<String> colSet = ro.getColSet();
        return new RelationalIterator(client, th, colSet);
    }

    @Override
    public RelationalIterator batchQuery(String tableName, ReadOption ro) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tableName);
        if (th == null) {
            throw new TabletException("no table with name " + tableName);
        }
        //TODO
        return new RelationalIterator();
    }

    @Override
    public RelationalIterator query(String tableName, ReadOption ro) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tableName);
        if (th == null) {
            throw new TabletException("no table with name " + tableName);
        }
        String idxName = "";
        String idxValue = "";
        Iterator<Map.Entry<String, Object>> iter = ro.getIndex().entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next();
            idxName = entry.getKey();
            idxValue = entry.getValue().toString();
            break;
        }
        idxValue = validateKey(idxValue);
        int pid = TableClientCommon.computePidByKey(idxValue, th.getPartitions().length);
        Set<String> colSet = ro.getColSet();
        return queryRelationTable(th.getTableInfo().getTid(), pid, idxValue, idxName, null, th, colSet);
    }

    @Override
    public Object[] getRow(String tname, String key, long time, Tablet.GetType type) throws TimeoutException, TabletException {
        return getRow(tname, key, null, time, null, type);
    }

    @Override
    public ByteString get(String tname, String key, long time) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }

        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        ByteString response = get(th.getTableInfo().getTid(), pid, key, null, time, null, null, th, 0l, null);
        return response;
    }

    /**
     * validate key
     * rewrite key if rtidb client config set isHandleNull {@code true}
     *
     * @param key
     * @return
     * @throws TabletException if key is null or empty
     */
    private String validateKey(String key) throws TabletException {
        if (key == null || key.isEmpty()) {
            if (client.getConfig().isHandleNull()) {
                key = key == null ? RTIDBClientConfig.NULL_STRING : key.isEmpty() ? RTIDBClientConfig.EMPTY_STRING : key;
            } else {
                throw new TabletException("key is null or empty");
            }
        }
        return key;
    }

    private RelationalIterator queryRelationTable(int tid, int pid, String key, String idxName, Tablet.GetType type,
                                                  TableHandler th, Set<String> colSet) throws TabletException {
        key = validateKey(key);
        PartitionHandler ph = th.getHandler(pid);
        TabletServer ts = ph.getReadHandler(th.getReadStrategy());
        if (ts == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.GetRequest.Builder builder = Tablet.GetRequest.newBuilder();

        builder.setTid(tid);
        builder.setPid(pid);
        builder.setKey(key);
        if (type != null) builder.setType(type);
        if (idxName != null && !idxName.isEmpty()) {
            builder.setIdxName(idxName);
        }
        Tablet.GetRequest request = builder.build();
        Tablet.GetResponse response = ts.get(request);
        ByteString bs = null;
        if (response != null && response.getCode() == 0) {
            if (th.getTableInfo().hasCompressType() && th.getTableInfo().getCompressType() == NS.CompressType.kSnappy) {
                byte[] uncompressed = Compress.snappyUnCompress(response.getValue().toByteArray());
                bs = ByteString.copyFrom(uncompressed);
            } else {
                bs = response.getValue();
            }
        } else if (response != null && response.getCode() != 0) {
            return new RelationalIterator();
        }
        RelationalIterator it = new RelationalIterator(bs, th, colSet);
//        it.setCount(response.getCount());
//        if (th.getTableInfo().hasCompressType()) {
//            it.setCompressType(th.getTableInfo().getCompressType());
//        }
        return it;
    }

    private ByteString get(int tid, int pid, String key, String idxName, long time, String tsName, Tablet.GetType type,
                           TableHandler th,
                           long et, Tablet.GetType etType) throws TabletException {
        key = validateKey(key);
        PartitionHandler ph = th.getHandler(pid);
        TabletServer ts = ph.getReadHandler(th.getReadStrategy());
        if (ts == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.GetRequest.Builder builder = Tablet.GetRequest.newBuilder();
        builder.setTid(tid);
        builder.setPid(pid);
        builder.setKey(key);
        builder.setTs(time);
        builder.setEt(et);
        if (type != null) builder.setType(type);
        if (etType != null) builder.setEtType(etType);
        if (idxName != null && !idxName.isEmpty()) {
            builder.setIdxName(idxName);
        }
        if (tsName != null && !tsName.isEmpty()) {
            builder.setTsName(tsName);
        }
        Tablet.GetRequest request = builder.build();
        Tablet.GetResponse response = ts.get(request);
        if (response != null && response.getCode() == 0) {
            if (th.getTableInfo().hasCompressType() && th.getTableInfo().getCompressType() == NS.CompressType.kSnappy) {
                byte[] uncompressed = Compress.snappyUnCompress(response.getValue().toByteArray());
                return ByteString.copyFrom(uncompressed);
            } else {
                return response.getValue();
            }
        }
        if (response != null) {
            if (response.getCode() == 109) {
                return null;
            }
            throw new TabletException(response.getCode(), response.getMsg());
        }
        return null;
    }

    @Override
    public int count(String tname, String key) throws TimeoutException, TabletException {
        return count(tname, key, null, null, false);
    }

    @Override
    public int count(String tname, String key, boolean filter_expired_data) throws TimeoutException, TabletException {
        return count(tname, key, null, null, filter_expired_data);
    }

    @Override
    public int count(String tname, String key, String idxName) throws TimeoutException, TabletException {
        return count(tname, key, idxName, null, false);
    }

    @Override
    public int count(String tname, String key, String idxName, boolean filter_expired_data) throws TimeoutException, TabletException {
        return count(tname, key, idxName, null, filter_expired_data);
    }

    @Override
    public int count(String tname, String key, long st, long et) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, key, null, null, true, th, st, et);
    }

    @Override
    public int count(String tname, String key, String idxName, long st, long et) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, key, idxName, null, true, th, st, et);
    }

    @Override
    public int count(String tname, String key, String idxName, String tsName, long st, long et) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, key, idxName, tsName, true, th, st, et);
    }


    @Override
    public int count(String tname, String key, String idxName, String tsName, boolean filter_expired_data)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, key, idxName, tsName, filter_expired_data, th, 0, 0);
    }

    @Override
    public int count(String tname, Map<String, Object> keyMap, String idxName, String tsName, boolean filter_expired_data)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyMap, list, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, combinedKey, idxName, tsName, filter_expired_data, th, 0, 0);
    }

    @Override
    public int count(String tname, Map<String, Object> keyMap, String idxName, String tsName, long st, long et)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyMap, list, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, combinedKey, idxName, tsName, true, th, st, et);
    }

    @Override
    public int count(String tname, Object[] keyArr, String idxName, String tsName, boolean filter_expired_data)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        if (keyArr.length != list.size()) {
            throw new TabletException("check key number failed");
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyArr, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return count(th.getTableInfo().getTid(), pid, combinedKey, idxName, tsName, filter_expired_data, th, 0, 0);
    }

    @Override
    public int count(int tid, int pid, String key, boolean filter_expired_data) throws TimeoutException, TabletException {
        return count(tid, pid, key, null, filter_expired_data);
    }

    @Override
    public int count(int tid, int pid, String key, String idxName, boolean filter_expired_data) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("no table with tid" + tid);
        }
        return count(tid, pid, key, idxName, null, filter_expired_data, th, 0, 0);
    }

    private int count(int tid, int pid, String key, String idxName, String tsName, boolean filter_expired_data,
                      TableHandler th, long st, long et) throws TimeoutException, TabletException {
        key = validateKey(key);
        PartitionHandler ph = th.getHandler(pid);
        TabletServer ts = ph.getReadHandler(th.getReadStrategy());
        if (ts == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.CountRequest.Builder builder = Tablet.CountRequest.newBuilder();
        builder.setTid(tid);
        builder.setPid(pid);
        builder.setKey(key);
        builder.setFilterExpiredData(filter_expired_data);
        if (client.getConfig().isRemoveDuplicateByTime()) {
            builder.setEnableRemoveDuplicatedRecord(true);
        }
        builder.setEt(et);
        builder.setSt(st);
        if (idxName != null && !idxName.isEmpty()) {
            builder.setIdxName(idxName);
        }
        if (tsName != null && !tsName.isEmpty()) {
            builder.setTsName(tsName);
        }
        Tablet.CountRequest request = builder.build();
        Tablet.CountResponse response = ts.count(request);
        if (response != null && response.getCode() == 0) {
            return response.getCount();
        }
        if (response != null) {
            throw new TabletException(response.getCode(), response.getMsg());
        }
        throw new TabletException("rtidb internal server error");
    }

    @Override
    public boolean delete(String tableName, Map<String, Object> conditionColumns) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tableName);
        if (th == null) {
            throw new TabletException("no table with name " + tableName);
        }
        String idxName = "";
        String idxValue = "";
        Iterator<Map.Entry<String, Object>> iter = conditionColumns.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next();
            idxName = entry.getKey();
            idxValue = entry.getValue().toString();
            break;
        }
        idxValue = validateKey(idxValue);
        int pid = TableClientCommon.computePidByKey(idxValue, th.getPartitions().length);
        return delete(th.getTableInfo().getTid(), pid, idxValue, idxName, th);
    }

    @Override
    public boolean delete(String tname, String key) throws TimeoutException, TabletException {
        return delete(tname, key, null);
    }

    @Override
    public boolean delete(String tname, String key, String idxName) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return delete(th.getTableInfo().getTid(), pid, key, idxName, th);

    }

    @Override
    public boolean delete(int tid, int pid, String key) throws TimeoutException, TabletException {
        return delete(tid, pid, key, null);
    }

    @Override
    public boolean delete(int tid, int pid, String key, String idxName) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("no table with tid" + tid);
        }
        return delete(tid, pid, key, idxName, th);
    }

    private boolean delete(int tid, int pid, String key, String idxName, TableHandler th) throws TimeoutException, TabletException {
        key = validateKey(key);
        PartitionHandler ph = th.getHandler(pid);
        TabletServer ts = ph.getLeader();
        if (ts == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.DeleteRequest.Builder builder = Tablet.DeleteRequest.newBuilder();
        builder.setTid(tid);
        builder.setPid(pid);
        builder.setKey(key);
        if (idxName != null && !idxName.isEmpty()) {
            builder.setIdxName(idxName);
        }
        Tablet.DeleteRequest request = builder.build();
        Tablet.GeneralResponse response = ts.delete(request);
        if (response != null && response.getCode() == 0) {
            return true;
        }
        if (response != null) {
            throw new TabletException(response.getCode(), response.getMsg());
        }
        return false;
    }

    @Override
    public KvIterator scan(int tid, int pid, String key, long st, long et) throws TimeoutException, TabletException {
        return scan(tid, pid, key, null, st, et, 0);
    }

    @Override
    public KvIterator scan(int tid, int pid, String key, int limit) throws TimeoutException, TabletException {
        return scan(tid, pid, key, null, 0, 0, limit);
    }

    @Override
    public KvIterator scan(int tid, int pid, String key, long st, long et, int limit) throws TimeoutException, TabletException {
        return scan(tid, pid, key, null, st, et, limit);
    }

    @Override
    public KvIterator scan(int tid, int pid, String key, String idxName, long st, long et) throws TimeoutException, TabletException {
        return scan(tid, pid, key, idxName, st, et, 0);
    }

    @Override
    public KvIterator scan(int tid, int pid, String key, String idxName, long st, long et, int limit) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("no table with tid" + tid);
        }
        return scan(tid, pid, key, idxName, st, et, null, limit, 0, th);
    }

    @Override
    public KvIterator scan(int tid, int pid, String key, String idxName, int limit) throws TimeoutException, TabletException {
        return scan(tid, pid, key, idxName, 0, 0, limit);
    }

    @Override
    public KvIterator scan(String tname, String key, long st, long et) throws TimeoutException, TabletException {

        return scan(tname, key, null, st, et, 0);
    }

    @Override
    public KvIterator scan(String tname, String key, int limit) throws TimeoutException, TabletException {
        return scan(tname, key, null, 0, 0, limit);
    }

    @Override
    public KvIterator scan(String tname, String key, long st, long et, int limit) throws TimeoutException, TabletException {
        return scan(tname, key, null, st, et, limit);
    }

    @Override
    public KvIterator scan(String tname, String key, String idxName, long st, long et)
            throws TimeoutException, TabletException {
        return scan(tname, key, idxName, st, et, 0);
    }

    @Override
    public KvIterator scan(String tname, String key, String idxName, long st, long et, int limit) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, key, idxName, st, et, null, limit, 0, th);
    }

    @Override
    public KvIterator scan(String tname, String key, String idxName, int limit) throws TimeoutException, TabletException {
        return scan(tname, key, idxName, 0, 0, limit);
    }

    @Override
    public KvIterator scan(String tname, String key, String idxName, long st, long et, String tsName, int limit)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, key, idxName, st, et, tsName, limit, 0, th);
    }

    @Override
    public KvIterator scan(String tname, Object[] keyArr, String idxName, long st, long et, String tsName, int limit)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        if (keyArr.length != list.size()) {
            throw new TabletException("check key number failed");
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyArr, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, combinedKey, idxName, st, et, tsName, limit, 0, th);
    }

    @Override
    public KvIterator scan(String tname, Map<String, Object> keyMap,
                           String idxName, long st, long et, String tsName, int limit)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyMap, list, client.getConfig().isHandleNull());
        int pid = TableClientCommon.computePidByKey(combinedKey, th.getPartitions().length);
        return scan(th.getTableInfo().getTid(), pid, combinedKey, idxName, st, et, tsName, limit, 0, th);
    }

    @Override
    public KvIterator traverse(String tname, String idxName, String tsName) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        TraverseKvIterator it = new TraverseKvIterator(client, th, idxName, tsName);
        it.next();
        return it;
    }

    @Override
    public KvIterator traverse(String tname, String idxName) throws TimeoutException, TabletException {
        return traverse(tname, idxName, null);
    }

    @Override
    public KvIterator traverse(String tname) throws TimeoutException, TabletException {
        return traverse(tname, null, null);
    }

    @Override
    public KvIterator scan(int tid, int pid, Object[] keyArr, String idxName, long st, long et, String tsName)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("no table with tid" + tid);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        if (keyArr.length != list.size()) {
            throw new TabletException("check key number failed");
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyArr, client.getConfig().isHandleNull());
        return scan(tid, pid, combinedKey, idxName, st, et, tsName, 0, 0, th);
    }

    @Override
    public KvIterator scan(int tid, int pid, Map<String, Object> keyMap, String idxName,
                           long st, long et, String tsName, int limit) throws TimeoutException, TabletException {
        return scan(tid, pid, keyMap, idxName, st, et, tsName, limit, 0);
    }

    @Override
    public KvIterator scan(int tid, int pid, Map<String, Object> keyMap, String idxName,
                           long st, long et, String tsName, int limit, int atLeast) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("no table with tid" + tid);
        }
        List<String> list = th.getKeyMap().get(idxName);
        if (list == null) {
            throw new TabletException("no index name in table" + idxName);
        }
        String combinedKey = TableClientCommon.getCombinedKey(keyMap, list, client.getConfig().isHandleNull());
        return scan(tid, pid, combinedKey, idxName, st, et, tsName, limit, atLeast, th);
    }


    private KvIterator scan(int tid, int pid, String key, String idxName,
                            long st, long et, String tsName, int limit, int atLeast, TableHandler th) throws TimeoutException, TabletException {
        key = validateKey(key);
        PartitionHandler ph = th.getHandler(pid);
        TabletServer ts = ph.getReadHandler(th.getReadStrategy());
        if (ts == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.ScanRequest.Builder builder = Tablet.ScanRequest.newBuilder();
        if (key != null) {
            builder.setPk(key);
        }
        builder.setTid(tid);
        builder.setEt(et);
        builder.setSt(st);
        builder.setPid(pid);
        builder.setLimit(limit);
        builder.setAtleast(atLeast);
        if (idxName != null && !idxName.isEmpty()) {
            builder.setIdxName(idxName);
        }
        if (tsName != null && !tsName.isEmpty()) {
            builder.setTsName(tsName);
        }
        if (client.getConfig().isRemoveDuplicateByTime()) {
            builder.setEnableRemoveDuplicatedRecord(true);
        }
        Tablet.ScanRequest request = builder.build();
        Tablet.ScanResponse response = ts.scan(request);
        if (response != null && response.getCode() == 0) {
            Long network = null;
            DefaultKvIterator it = null;
            if (th.getSchemaMap().size() > 0) {
                it = new DefaultKvIterator(response.getPairs(), th);

            } else {
                it = new DefaultKvIterator(response.getPairs(), th.getSchema(), network);
            }
            it.setCount(response.getCount());
            if (th.getTableInfo().hasCompressType()) {
                it.setCompressType(th.getTableInfo().getCompressType());
            }
            return it;
        }
        if (response != null) {
            throw new TabletException(response.getCode(), response.getMsg());
        }
        throw new TabletException("rtidb internal server error");
    }

    @Override
    public boolean put(String name, String key, long time, byte[] bytes) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(name);
        if (th == null) {
            throw new TabletException("no table with name " + name);
        }
        if (!th.getSchema().isEmpty()) {
            throw new TabletException("fail to put the schema table " + th.getTableInfo().getName() + " in the way of putting kv table");
        }
        key = validateKey(key);
        int pid = TableClientCommon.computePidByKey(key, th.getPartitions().length);
        return put(th.getTableInfo().getTid(), pid, key, time, bytes, th);
    }

    @Override
    public boolean put(String name, long time, Object[] row) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(name);
        if (th == null) {
            throw new TabletException("no table with name " + name);
        }
        if (th.hasTsCol()) {
//            throw new TabletException("has ts column. should not set time");
            return put(name, row);
        }
        return put(name, time, row, null);
    }

    @Override
    public boolean put(String name, Object[] row) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(name);
        if (th == null) {
            throw new TabletException("no table with name " + name);
        }
        if (row.length > th.getSchema().size() + th.getSchemaMap().size()) {
            row = Arrays.copyOf(row, th.getSchema().size() + th.getSchemaMap().size());
        }
        List<Tablet.TSDimension> tsDimensions = TableClientCommon.parseArrayInput(row, th);
        return put(name, 0, row, tsDimensions);
    }

    private boolean put(String name, long time, Object[] row, List<Tablet.TSDimension> ts) throws TimeoutException, TabletException {
        boolean handleNull = client.getConfig().isHandleNull();
        TableHandler th = client.getHandler(name);
        if (th == null) {
            throw new TabletException("no table with name " + name);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        ByteBuffer buffer = null;
        if (row.length == th.getSchema().size()) {
            buffer = RowCodec.encode(row, th.getSchema());
        } else {
            List<ColumnDesc> columnDescs = th.getSchemaMap().get(row.length);
            if (columnDescs == null) {
                throw new TabletException("no schema for column count " + row.length);
            }
            int modifyTimes = row.length - th.getSchema().size();
            if (row.length > th.getSchema().size() + th.getSchemaMap().size()) {
                modifyTimes = th.getSchemaMap().size();
            }
            buffer = RowCodec.encode(row, columnDescs, modifyTimes);
        }
        Map<Integer, List<Tablet.Dimension>> mapping = TableClientCommon.fillPartitionTabletDimension(row, th, handleNull);
        Iterator<Map.Entry<Integer, List<Tablet.Dimension>>> it = mapping.entrySet().iterator();
        boolean ret = true;
        while (it.hasNext()) {
            Map.Entry<Integer, List<Tablet.Dimension>> entry = it.next();
            ret = ret && put(th.getTableInfo().getTid(), entry.getKey(), null,
                    time, entry.getValue(), ts, buffer, th);
        }
        return ret;
    }

    private boolean putRelationTable(String name, Map<String, Object> row) throws TabletException {
        TableHandler th = client.getHandler(name);
        if (th == null) {
            throw new TabletException("no table with name " + name);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        ByteBuffer buffer;
        List<ColumnDesc> schema;
        if (row.size() == th.getSchema().size()) {
            schema = th.getSchema();
            buffer = RowBuilder.encode(row, th.getSchema());
        } else {
            schema = th.getSchemaMap().get(row.size());
            if (schema == null) {
                throw new TabletException("no schema for column count " + row.size());
            }
            buffer = RowBuilder.encode(row, schema);
        }
        String pk = RowCodecCommon.getPrimaryKey(row, th.getTableInfo().getColumnKeyList(), schema);
        int pid = TableClientCommon.computePidByKey(pk, th.getPartitions().length);
        return putRelationTable(th.getTableInfo().getTid(), pid, buffer, th);
    }

    private boolean put(int tid, int pid, String key, long time, byte[] bytes, TableHandler th) throws TabletException {
        return put(tid, pid, key, time, null, null, ByteBuffer.wrap(bytes), th);
    }

    private boolean putRelationTable(int tid, int pid, ByteBuffer row, TableHandler th) throws TabletException {
        PartitionHandler ph = th.getHandler(pid);
        if (th.getTableInfo().hasCompressType() && th.getTableInfo().getCompressType() == NS.CompressType.kSnappy) {
            byte[] data = row.array();
            byte[] compressed = Compress.snappyCompress(data);
            if (compressed == null) {
                throw new TabletException("snappy compress error");
            }
            ByteBuffer buffer = ByteBuffer.wrap(compressed);
            row = buffer;
        }
        TabletServer tablet = ph.getLeader();
        if (tablet == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.PutRequest.Builder builder = Tablet.PutRequest.newBuilder();
        builder.setPid(pid);
        builder.setTid(tid);
        row.rewind();
        builder.setValue(ByteBufferNoCopy.wrap(row.asReadOnlyBuffer()));

        Tablet.PutRequest request = builder.build();
        Tablet.PutResponse response = tablet.put(request);
        if (response != null && response.getCode() == 0) {
            return true;
        }
        if (response != null) {
            throw new TabletException(response.getCode(), response.getMsg());
        }
        return false;
    }

    private boolean put(int tid, int pid,
                        String key, long time,
                        List<Tablet.Dimension> ds,
                        List<Tablet.TSDimension> ts,
                        ByteBuffer row, TableHandler th) throws TabletException {
        if ((ds == null || ds.isEmpty()) && (key == null || key.isEmpty())) {
            throw new TabletException("key is null or empty");
        }
        if (time == 0 && (ts == null || ts.isEmpty())) {
            throw new TabletException("ts is null or empty");
        }
        PartitionHandler ph = th.getHandler(pid);
        if (th.getTableInfo().hasCompressType() && th.getTableInfo().getCompressType() == NS.CompressType.kSnappy) {
            byte[] data = row.array();
            byte[] compressed = Compress.snappyCompress(data);
            if (compressed == null) {
                throw new TabletException("snappy compress error");
            }
            ByteBuffer buffer = ByteBuffer.wrap(compressed);
            row = buffer;
        }
        TabletServer tablet = ph.getLeader();
        if (tablet == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.PutRequest.Builder builder = Tablet.PutRequest.newBuilder();
        builder.setPid(pid);
        builder.setTid(tid);
        if (time != 0) {
            builder.setTime(time);
        }
        if (ts != null) {
            for (Tablet.TSDimension tsDim : ts) {
                builder.addTsDimensions(tsDim);
            }
        }
        if (key != null) {
            builder.setPk(key);
        }
        if (ds != null) {
            for (Tablet.Dimension dim : ds) {
                builder.addDimensions(dim);
            }
        }
        row.rewind();
        builder.setValue(ByteBufferNoCopy.wrap(row.asReadOnlyBuffer()));
        Tablet.PutRequest request = builder.build();
        Tablet.PutResponse response = tablet.put(request);
        if (response != null && response.getCode() == 0) {
            return true;
        }
        if (response != null) {
            throw new TabletException(response.getCode(), response.getMsg());
        }
        return false;
    }

    @Override
    public boolean put(String tname, Map<String, Object> row) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        List<Tablet.TSDimension> tsDimensions = new ArrayList<Tablet.TSDimension>();
        Object[] arrayRow = null;
        if (row.size() > th.getSchema().size() && th.getSchemaMap().size() > 0) {
            if (row.size() > th.getSchema().size() + th.getSchemaMap().size()) {
                arrayRow = new Object[th.getSchema().size() + th.getSchemaMap().size()];
            } else {
                arrayRow = new Object[row.size()];
            }
        } else {
            arrayRow = new Object[th.getSchema().size()];
        }
        TableClientCommon.parseMapInput(row, th, arrayRow, tsDimensions);
        return put(tname, 0, arrayRow, tsDimensions);
    }

    @Override
    public boolean put(String tname, Map<String, Object> row, WriteOption wo) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        //TODO: resolve wo
        return putRelationTable(tname, row);

    }

    private boolean updateRequest(TableHandler th, int pid, List<ColumnDesc> newCdSchema, List<ColumnDesc> newValueSchema,
                                  ByteBuffer conditionBuffer, ByteBuffer valueBuffer) throws TabletException {
        PartitionHandler ph = th.getHandler(pid);
        if (th.getTableInfo().hasCompressType() && th.getTableInfo().getCompressType() == NS.CompressType.kSnappy) {
            byte[] data = conditionBuffer.array();
            byte[] compressed = Compress.snappyCompress(data);
            if (compressed == null) {
                throw new TabletException("snappy compress error");
            }
            conditionBuffer = ByteBuffer.wrap(compressed);

            byte[] data2 = valueBuffer.array();
            byte[] compressed2 = Compress.snappyCompress(data2);
            if (compressed2 == null) {
                throw new TabletException("snappy compress error");
            }
            valueBuffer = ByteBuffer.wrap(compressed2);
        }
        TabletServer tablet = ph.getLeader();
        int tid = th.getTableInfo().getTid();
        if (tablet == null) {
            throw new TabletException("Cannot find available tabletServer with tid " + tid);
        }
        Tablet.UpdateRequest.Builder builder = Tablet.UpdateRequest.newBuilder();
        builder.setTid(tid);
        builder.setPid(pid);
        {
            Tablet.Columns.Builder conditionBuilder = Tablet.Columns.newBuilder();
            for (ColumnDesc col : newCdSchema) {
                conditionBuilder.addName(col.getName());
            }
            conditionBuffer.rewind();
            conditionBuilder.setValue(ByteBufferNoCopy.wrap(conditionBuffer.asReadOnlyBuffer()));
            builder.setConditionColumns(conditionBuilder.build());
        }
        {
            Tablet.Columns.Builder valueBuilder = Tablet.Columns.newBuilder();
            for (ColumnDesc col : newValueSchema) {
                valueBuilder.addName(col.getName());
            }
            valueBuffer.rewind();
            valueBuilder.setValue(ByteBufferNoCopy.wrap(valueBuffer.asReadOnlyBuffer()));
            builder.setValueColumns(valueBuilder.build());
        }
        Tablet.UpdateRequest request = builder.build();
        Tablet.GeneralResponse response = tablet.update(request);
        if (response != null && response.getCode() == 0) {
            return true;
        }
        if (response != null) {
            throw new TabletException(response.getCode(), response.getMsg());
        }
        return false;
    }

    private List<ColumnDesc> getSchemaData(Map<String, Object> columns, List<ColumnDesc> schema) {
        List<ColumnDesc> newSchema = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            ColumnDesc columnDesc = schema.get(i);
            String colName = columnDesc.getName();
            if (columns.containsKey(colName)) {
                newSchema.add(columnDesc);
            }
        }
        return newSchema;
    }

    @Override
    public boolean update(String tableName, Map<String, Object> conditionColumns, Map<String, Object> valueColumns, WriteOption wo)
            throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tableName);
        if (th == null) {
            throw new TabletException("no table with name " + tableName);
        }
        if (conditionColumns == null || conditionColumns.isEmpty()) {
            throw new TabletException("conditionColumns is null or empty");
        }
        if (valueColumns == null || valueColumns.isEmpty()) {
            throw new TabletException("valueColumns is null or empty");
        }
        String idxName = "";
        String idxValue = "";
        Iterator<Map.Entry<String, Object>> iter = conditionColumns.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next();
            idxName = entry.getKey();
            idxValue = entry.getValue().toString();
            break;
        }
        idxValue = validateKey(idxValue);
        int pid = TableClientCommon.computePidByKey(idxValue, th.getPartitions().length);

        List<ColumnDesc> newCdSchema = getSchemaData(conditionColumns, th.getSchema());
        ByteBuffer conditionBuffer = RowBuilder.encode(conditionColumns, newCdSchema);

        List<ColumnDesc> newValueSchema = getSchemaData(valueColumns, th.getSchema());
        ByteBuffer valueBuffer = RowBuilder.encode(valueColumns, newValueSchema);

        return updateRequest(th, pid, newCdSchema, newValueSchema, conditionBuffer, valueBuffer);
    }

    @Override
    public boolean put(String tname, long time, Map<String, Object> row) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        if (th.hasTsCol()) {
//            throw new TabletException("has ts column. should not set time");
            return put(tname, row);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        Object[] arrayRow = null;
        if (row.size() > th.getSchema().size() && th.getSchemaMap().size() > 0) {
            int columnSize = row.size();
            if (row.size() > th.getSchema().size() + th.getSchemaMap().size()) {
                columnSize = th.getSchema().size() + th.getSchemaMap().size();
            }
            arrayRow = new Object[columnSize];
            List<ColumnDesc> schema = th.getSchemaMap().get(columnSize);
            for (int i = 0; i < schema.size(); i++) {
                arrayRow[i] = row.get(schema.get(i).getName());
            }
        } else {
            arrayRow = new Object[th.getSchema().size()];
            for (int i = 0; i < th.getSchema().size(); i++) {
                arrayRow[i] = row.get(th.getSchema().get(i).getName());
            }
        }
        return put(tname, time, arrayRow);
    }

    @Override
    public List<ColumnDesc> getSchema(String tname) throws TabletException {
        TableHandler th = client.getHandler(tname);
        if (th == null) {
            throw new TabletException("no table with name " + tname);
        }
        if (th.getSchemaMap().size() == 0) {
            return th.getSchema();
        } else {
            return th.getSchemaMap().get(th.getSchema().size() + th.getSchemaMap().size());
        }
    }

    @Override
    public boolean put(int tid, int pid, long time, Map<String, Object> row) throws TimeoutException, TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("fail to find table with id " + tid);
        }
        if (row == null) {
            throw new TabletException("putting data is null");
        }
        Object[] arrayRow = null;
        if (row.size() > th.getSchema().size() && th.getSchemaMap().size() > 0) {
            int columnSize = row.size();
            if (row.size() > th.getSchema().size() + th.getSchemaMap().size()) {
                columnSize = th.getSchema().size() + th.getSchemaMap().size();
            }
            arrayRow = new Object[columnSize];
            List<ColumnDesc> schema = th.getSchemaMap().get(columnSize);
            for (int i = 0; i < schema.size(); i++) {
                arrayRow[i] = row.get(schema.get(i).getName());
            }
        } else {
            arrayRow = new Object[th.getSchema().size()];
            for (int i = 0; i < th.getSchema().size(); i++) {
                arrayRow[i] = row.get(th.getSchema().get(i).getName());
            }
        }
        return put(tid, pid, time, arrayRow);
    }

    @Override
    public List<ColumnDesc> getSchema(int tid) throws TabletException {
        TableHandler th = client.getHandler(tid);
        if (th == null) {
            throw new TabletException("fail to find table with id " + tid);
        }
        if (th.getSchemaMap().size() == 0) {
            return th.getSchema();
        } else {
            return th.getSchemaMap().get(th.getSchema().size() + th.getSchemaMap().size());
        }
    }


}
