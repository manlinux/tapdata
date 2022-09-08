package io.tapdata.connector.dameng.cdc.logminer.bean;

import java.sql.ResultSet;
import java.sql.SQLException;

public class OracleInstanceInfo {


  public String instanceName;

  public OracleInstanceInfo(ResultSet resultSet) throws SQLException {
    this.instanceName = resultSet.getString("INSTANCE_NAME");
  }


  public String getInstanceName() {
    return instanceName;
  }

  public void setInstanceName(String instanceName) {
    this.instanceName = instanceName;
  }
}
