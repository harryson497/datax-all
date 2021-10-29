package com.alibaba.datax.plugin.rdbms.reader;

/**
 * 编码，时区等配置，暂未定.
 */
public final class Key {
    public final static String JDBC_URL = "jdbcUrl";

    public final static String USERNAME = "username";

    public final static String PASSWORD = "password";

    public final static String TABLE = "table";
    
    public final static String MANDATORY_ENCODING = "mandatoryEncoding";

    // 是数组配置
    public final static String COLUMN = "column";
    
    public final static String COLUMN_LIST = "columnList";

    public final static String WHERE = "where";

    public final static String HINT = "hint";

    public final static String SPLIT_PK = "splitPk";
    
    public final static String SPLIT_MODE = "splitMode";
    
    public final static String SAMPLE_PERCENTAGE = "samplePercentage";

    public final static String QUERY_SQL = "querySql";

    public final static String SPLIT_PK_SQL = "splitPkSql";


    public final static String PRE_SQL = "preSql";

    public final static String POST_SQL = "postSql";

    public final static String CHECK_SLAVE = "checkSlave";

	public final static String SESSION = "session";

	public final static String DBNAME = "dbName";

    public final static String DRYRUN = "dryRun";
    //hive reader
    public final static String DEFAULT_FS = "defaultFS";//defaultfs

    public final static String FIELDDELIMITER = "fielddelimiter";

//    public final static String HIVE_SQL = "hivesql";
//    修改为驼峰命名规则
    public final static String HIVE_SQL = "hiveSql";


    public final static String HIVE_USER = "hiveuser";

    public final static String HIVE_PASSWORD = "hivepassword";

//    public final static String HIVE_JDBC = "hivejdbc";
//    修改为驼峰命名规则
    public final static String HIVE_JDBC = "hiveJdbc";

    //TODO
    public final static String FILETYPE = "fileType";
    public final static String HADOOP_CONFIG = "hadoopConfig";
    public final static String HAVE_KERBEROS = "haveKerberos";
    public final static String KERBEROS_KEYTAB_FILE_PATH = "kerberosKeytabFilePath";
    public final static String KERBEROS_PRINCIPAL = "kerberosPrincipal";

}