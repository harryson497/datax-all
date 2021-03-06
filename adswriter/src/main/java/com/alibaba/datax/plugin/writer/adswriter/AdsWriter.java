package com.alibaba.datax.plugin.writer.adswriter;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DataBaseType;
import com.alibaba.datax.plugin.rdbms.writer.util.WriterUtil;
import com.alibaba.datax.plugin.writer.adswriter.ads.ColumnInfo;
import com.alibaba.datax.plugin.writer.adswriter.ads.TableInfo;
import com.alibaba.datax.plugin.writer.adswriter.insert.AdsInsertProxy;
import com.alibaba.datax.plugin.writer.adswriter.insert.AdsInsertUtil;
import com.alibaba.datax.plugin.writer.adswriter.load.AdsHelper;
import com.alibaba.datax.plugin.writer.adswriter.load.TableMetaHelper;
import com.alibaba.datax.plugin.writer.adswriter.load.TransferProjectConf;
import com.alibaba.datax.plugin.writer.adswriter.odps.TableMeta;
import com.alibaba.datax.plugin.writer.adswriter.util.AdsUtil;
import com.alibaba.datax.plugin.writer.adswriter.util.Constant;
import com.alibaba.datax.plugin.writer.adswriter.util.Key;
import com.alibaba.datax.plugin.writer.odpswriter.OdpsWriter;
import com.aliyun.odps.Instance;
import com.aliyun.odps.Odps;
import com.aliyun.odps.OdpsException;
import com.aliyun.odps.account.Account;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.task.SQLTask;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AdsWriter extends Writer {

    public static class Job extends Writer.Job {
        private static final Logger LOG = LoggerFactory.getLogger(Writer.Job.class);
        public final static String ODPS_READER = "odpsreader";

        private OdpsWriter.Job odpsWriterJobProxy = new OdpsWriter.Job();
        private Configuration originalConfig;
        private Configuration readerConfig;

        /**
         * ??????ads?????????ads helper
         */
        private AdsHelper adsHelper;
        /**
         * ??????odps?????????ads helper
         */
        private AdsHelper odpsAdsHelper;
        /**
         * ??????odps?????????????????????writer?????????parameter.odps??????
         */
        private TransferProjectConf transProjConf;
        private final int ODPSOVERTIME = 120000;
        private String odpsTransTableName;

        private String writeMode;
        private long startTime;

        @Override
        public void init() {
            startTime = System.currentTimeMillis();
            this.originalConfig = super.getPluginJobConf();
            this.writeMode = this.originalConfig.getString(Key.WRITE_MODE);
            if(null == this.writeMode) {
                LOG.warn("????????????[writeMode]??????,  ????????????load??????, load???????????????????????????");
                this.writeMode = Constant.LOADMODE;
                this.originalConfig.set(Key.WRITE_MODE, "load");
            }

            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                AdsUtil.checkNecessaryConfig(this.originalConfig, this.writeMode);
                loadModeInit();
            } else if(Constant.INSERTMODE.equalsIgnoreCase(this.writeMode) || Constant.STREAMMODE.equalsIgnoreCase(this.writeMode)) {
                AdsUtil.checkNecessaryConfig(this.originalConfig, this.writeMode);
                List<String> allColumns = AdsInsertUtil.getAdsTableColumnNames(originalConfig);
                AdsInsertUtil.dealColumnConf(originalConfig, allColumns);

                LOG.debug("After job init(), originalConfig now is:[\n{}\n]",
                        originalConfig.toJSON());
            } else {
                throw DataXException.asDataXException(AdsWriterErrorCode.INVALID_CONFIG_VALUE, "writeMode ????????? 'load' ?????? 'insert' ?????? 'stream'");
            }
        }

        private void loadModeInit() {
            this.adsHelper = AdsUtil.createAdsHelper(this.originalConfig);
            this.odpsAdsHelper = AdsUtil.createAdsHelperWithOdpsAccount(this.originalConfig);
            this.transProjConf = TransferProjectConf.create(this.originalConfig);
            // ????????????????????????????????????
            LOG.info(String
                    .format("%s%n%s%n%s",
                            "??????????????????odps->ads????????????, ?????????2????????????:",
                            "[1] ads??????????????????????????????????????????describe???select??????, ??????ads??????????????????odps????????????????????????????????????",
                            "[2] ????????????ads?????????????????????ak, ?????????????????????ads???????????????load data?????????, ????????????ads?????????????????????"));
            LOG.info(String
                    .format("%s%s%n%s%n%s",
                            "??????????????????rds(????????????odps?????????)->ads????????????, ??????????????????????????????odps??????????????????odps?????????->ads, ",
                            String.format("??????odps?????????%s,?????????????????????%s, ????????????:",
                                    this.transProjConf.getProject(),
                                    this.transProjConf.getAccount()),
                            "[1] ads???????????????????????????????????????(?????????odps?????????)???describe???select??????, ??????ads??????????????????odps???????????????????????????????????????????????????????????????????????????",
                            String.format("[2] ??????odps???????????????%s, ?????????????????????ads???????????????load data?????????, ????????????ads?????????????????????", this.transProjConf.getAccount())));

            /**
             * ????????????odps?????????ads?????????load data??????System.exit()
             */
            if (super.getPeerPluginName().equals(ODPS_READER)) {
                transferFromOdpsAndExit();
            }
            Account odpsAccount;
            odpsAccount = new AliyunAccount(transProjConf.getAccessId(), transProjConf.getAccessKey());

            Odps odps = new Odps(odpsAccount);
            odps.setEndpoint(transProjConf.getOdpsServer());
            odps.setDefaultProject(transProjConf.getProject());

            TableMeta tableMeta;
            try {
                String adsTable = this.originalConfig.getString(Key.ADS_TABLE);
                TableInfo tableInfo = adsHelper.getTableInfo(adsTable);
                int lifeCycle = this.originalConfig.getInt(Key.Life_CYCLE);
                tableMeta = TableMetaHelper.createTempODPSTable(tableInfo, lifeCycle);
                this.odpsTransTableName = tableMeta.getTableName();
                String sql = tableMeta.toDDL();
                LOG.info("????????????ODPS???????????? "+sql);
                Instance instance = SQLTask.run(odps, transProjConf.getProject(), sql, null, null);
                boolean terminated = false;
                int time = 0;
                while (!terminated && time < ODPSOVERTIME) {
                    Thread.sleep(1000);
                    terminated = instance.isTerminated();
                    time += 1000;
                }
                LOG.info("????????????ODPS???????????????");
            } catch (AdsException e) {
                throw DataXException.asDataXException(AdsWriterErrorCode.ODPS_CREATETABLE_FAILED, e);
            }catch (OdpsException e) {
                throw DataXException.asDataXException(AdsWriterErrorCode.ODPS_CREATETABLE_FAILED,e);
            } catch (InterruptedException e) {
                throw DataXException.asDataXException(AdsWriterErrorCode.ODPS_CREATETABLE_FAILED,e);
            }

            Configuration newConf = AdsUtil.generateConf(this.originalConfig, this.odpsTransTableName,
                    tableMeta, this.transProjConf);
            odpsWriterJobProxy.setPluginJobConf(newConf);
            odpsWriterJobProxy.init();
        }

        /**
         * ???reader???odps??????????????????call ads???load???????????????????????????
         * ???????????????????????????odps reader??????????????????????????????????????????
         * ??????accessId???accessKey????????????iao??????
         */
        private void transferFromOdpsAndExit() {
            this.readerConfig = super.getPeerPluginJobConf();
            String odpsTableName = this.readerConfig.getString(Key.ODPSTABLENAME);
            List<String> userConfiguredPartitions = this.readerConfig.getList(Key.PARTITION, String.class);

            if (userConfiguredPartitions == null) {
                userConfiguredPartitions = Collections.emptyList();
            }

            if(userConfiguredPartitions.size() > 1)
                throw DataXException.asDataXException(AdsWriterErrorCode.ODPS_PARTITION_FAILED, "");

            if(userConfiguredPartitions.size() == 0) {
                loadAdsData(adsHelper, odpsTableName,null);
            }else {
                loadAdsData(adsHelper, odpsTableName,userConfiguredPartitions.get(0));
            }
            System.exit(0);
        }

        // ????????????????????????????????? task ?????????pre ?????????????????????????????????
        @Override
        public void prepare() {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                //????????????odps??????
                this.odpsWriterJobProxy.prepare();
            } else {
                // ??????????????????????????????
                String adsTable = this.originalConfig.getString(Key.ADS_TABLE);
                List<String> preSqls = this.originalConfig.getList(Key.PRE_SQL,
                        String.class);
                List<String> renderedPreSqls = WriterUtil.renderPreOrPostSqls(
                        preSqls, adsTable);
                if (null != renderedPreSqls && !renderedPreSqls.isEmpty()) {
                    // ????????? preSql ???????????????????????????
                    this.originalConfig.remove(Key.PRE_SQL);
                    Connection preConn = AdsUtil.getAdsConnect(this.originalConfig);
                    LOG.info("Begin to execute preSqls:[{}]. context info:{}.",
                            StringUtils.join(renderedPreSqls, ";"),
                            this.originalConfig.getString(Key.ADS_URL));
                    WriterUtil.executeSqls(preConn, renderedPreSqls,
                            this.originalConfig.getString(Key.ADS_URL),
                            DataBaseType.ADS);
                    DBUtil.closeDBResources(null, null, preConn);
                }
            }
        }

        @Override
        public List<Configuration> split(int mandatoryNumber) {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                return this.odpsWriterJobProxy.split(mandatoryNumber);
            } else {
                List<Configuration> splitResult = new ArrayList<Configuration>();
                for(int i = 0; i < mandatoryNumber; i++) {
                    splitResult.add(this.originalConfig.clone());
                }
                return splitResult;
            }
        }

        // ????????????????????????????????? task ?????????post ?????????????????????????????????
        @Override
        public void post() {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                loadAdsData(odpsAdsHelper, this.odpsTransTableName, null);
                this.odpsWriterJobProxy.post();
            } else {
                // ??????????????????????????????
                String adsTable = this.originalConfig.getString(Key.ADS_TABLE);
                List<String> postSqls = this.originalConfig.getList(
                        Key.POST_SQL, String.class);
                List<String> renderedPostSqls = WriterUtil.renderPreOrPostSqls(
                        postSqls, adsTable);
                if (null != renderedPostSqls && !renderedPostSqls.isEmpty()) {
                    // ????????? preSql ???????????????????????????
                    this.originalConfig.remove(Key.POST_SQL);
                    Connection postConn = AdsUtil.getAdsConnect(this.originalConfig);
                    LOG.info(
                            "Begin to execute postSqls:[{}]. context info:{}.",
                            StringUtils.join(renderedPostSqls, ";"),
                            this.originalConfig.getString(Key.ADS_URL));
                    WriterUtil.executeSqls(postConn, renderedPostSqls,
                            this.originalConfig.getString(Key.ADS_URL),
                            DataBaseType.ADS);
                    DBUtil.closeDBResources(null, null, postConn);
                }
            }
        }

        @Override
        public void destroy() {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                this.odpsWriterJobProxy.destroy();
            } else {
                //insert mode do noting
            }
        }

        private void loadAdsData(AdsHelper helper, String odpsTableName, String odpsPartition) {

            String table = this.originalConfig.getString(Key.ADS_TABLE);
            String project;
            if (super.getPeerPluginName().equals(ODPS_READER)) {
                project = this.readerConfig.getString(Key.PROJECT);
            } else {
                project = this.transProjConf.getProject();
            }
            String partition = this.originalConfig.getString(Key.PARTITION);
            String sourcePath = AdsUtil.generateSourcePath(project,odpsTableName,odpsPartition);
            /**
             * ??????????????????????????????????????????unbox?????????NPE
             */
            boolean overwrite = this.originalConfig.getBool(Key.OVER_WRITE);
            try {
                String id = helper.loadData(table,partition,sourcePath,overwrite);
                LOG.info("ADS Load Data?????????????????????job id: " + id);
                boolean terminated = false;
                int time = 0;
                while(!terminated) {
                    Thread.sleep(120000);
                    terminated = helper.checkLoadDataJobStatus(id);
                    time += 2;
                    LOG.info("ADS ???????????????????????????????????????20??????????????????????????????,??????????????? "+ time+" ??????");
                }
                LOG.info("ADS ??????????????????");
            } catch (AdsException e) {
                if (super.getPeerPluginName().equals(ODPS_READER)) {
                    // TODO ???????????????
                    AdsWriterErrorCode.ADS_LOAD_ODPS_FAILED.setAdsAccount(helper.getUserName());
                    throw DataXException.asDataXException(AdsWriterErrorCode.ADS_LOAD_ODPS_FAILED,e);
                } else {
                    throw DataXException.asDataXException(AdsWriterErrorCode.ADS_LOAD_TEMP_ODPS_FAILED,e);
                }
            } catch (InterruptedException e) {
                throw DataXException.asDataXException(AdsWriterErrorCode.ODPS_CREATETABLE_FAILED,e);
            }
        }
    }

    public static class Task extends Writer.Task {
        private static final Logger LOG = LoggerFactory.getLogger(Writer.Task.class);
        private Configuration writerSliceConfig;
        private OdpsWriter.Task odpsWriterTaskProxy = new OdpsWriter.Task();

        
        private String writeMode;
        private String schema;
        private String table;
        private int columnNumber;
        // warn: ?????????insert, stream????????????, ??????load???????????????odps????????????
        private TableInfo tableInfo;

        @Override
        public void init() {
            writerSliceConfig = super.getPluginJobConf();
            this.writeMode = this.writerSliceConfig.getString(Key.WRITE_MODE);
            this.schema = writerSliceConfig.getString(Key.SCHEMA);
            this.table =  writerSliceConfig.getString(Key.ADS_TABLE);
            
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                odpsWriterTaskProxy.setPluginJobConf(writerSliceConfig);
                odpsWriterTaskProxy.init();
            } else if(Constant.INSERTMODE.equalsIgnoreCase(this.writeMode) || Constant.STREAMMODE.equalsIgnoreCase(this.writeMode)) {
                try {
                    this.tableInfo = AdsUtil.createAdsHelper(this.writerSliceConfig).getTableInfo(this.table);
                } catch (AdsException e) {
                    throw DataXException.asDataXException(AdsWriterErrorCode.CREATE_ADS_HELPER_FAILED, e);
                }
                List<String> allColumns = new ArrayList<String>();
                List<ColumnInfo> columnInfo =  this.tableInfo.getColumns();
                for (ColumnInfo eachColumn : columnInfo) {
                    allColumns.add(eachColumn.getName());
                }
                LOG.info("table:[{}] all columns:[\n{}\n].", this.writerSliceConfig.get(Key.ADS_TABLE), StringUtils.join(allColumns, ","));
                AdsInsertUtil.dealColumnConf(writerSliceConfig, allColumns);
                List<String> userColumns = writerSliceConfig.getList(Key.COLUMN, String.class);
                this.columnNumber = userColumns.size();
            } else {
                throw DataXException.asDataXException(AdsWriterErrorCode.INVALID_CONFIG_VALUE, "writeMode ????????? 'load' ?????? 'insert' ?????? 'stream'");
            }
        }

        @Override
        public void prepare() {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                odpsWriterTaskProxy.prepare();
            } else {
                //do nothing
            }
        }

        public void startWrite(RecordReceiver recordReceiver) {
            // ???????????????odps?????????->odps???????????????????????????, load?????????job post????????????
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                odpsWriterTaskProxy.setTaskPluginCollector(super.getTaskPluginCollector());
                odpsWriterTaskProxy.startWrite(recordReceiver);
            } else {
                // insert ??????
                List<String> columns = writerSliceConfig.getList(Key.COLUMN, String.class);
                Connection connection = AdsUtil.getAdsConnect(this.writerSliceConfig);
                TaskPluginCollector taskPluginCollector = super.getTaskPluginCollector();
                AdsInsertProxy proxy = new AdsInsertProxy(schema + "." + table, columns, writerSliceConfig, taskPluginCollector, this.tableInfo);
                proxy.startWriteWithConnection(recordReceiver, connection, columnNumber);
            }
        }

        @Override
        public void post() {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                odpsWriterTaskProxy.post();
            } else {
                //do noting until now
            }
        }

        @Override
        public void destroy() {
            if(Constant.LOADMODE.equalsIgnoreCase(this.writeMode)) {
                odpsWriterTaskProxy.destroy();
            } else {
                //do noting until now
            }
        }
    }

}
