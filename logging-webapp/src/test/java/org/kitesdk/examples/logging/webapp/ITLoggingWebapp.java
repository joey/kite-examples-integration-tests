/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.examples.logging.webapp;

import com.google.common.io.Resources;
import java.io.File;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.kitesdk.data.flume.Log4jAppender;
import org.kitesdk.examples.common.Cluster;
import org.kitesdk.examples.logging.CreateDataset;
import org.kitesdk.examples.logging.DeleteDataset;
import org.kitesdk.examples.logging.ReadDataset;
import java.io.IOException;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.client.fluent.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.kitesdk.examples.common.TestUtil.run;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.matchers.JUnitMatchers.containsString;

public class ITLoggingWebapp {

  @Rule
  public static TemporaryFolder folder = new TemporaryFolder();

  private static Cluster cluster;

  private Tomcat tomcat;

  @BeforeClass
  public static void startCluster() throws Exception {
    File flumeProperties = folder.newFile();
    FileUtils.copyURLToFile(Resources.getResource("flume.properties"), flumeProperties);
    cluster = new Cluster.Builder()
        .addHdfsService()
        .addFlumeAgent("tier1", flumeProperties)
        .build();
    cluster.start();
    Thread.sleep(5000L);
    configureLog4j();
  }

  @Before
  public void setUp() throws Exception {
    // delete dataset in case it already exists
    run(any(Integer.class), any(String.class), new DeleteDataset());

    startTomcat();
  }

  @AfterClass
  public static void stopCluster() throws Exception {
    cluster.stop();
  }

  private static void configureLog4j() throws Exception {
    // configuration is done programmatically and not in log4j.properties so that so we
    // can defer initialization to after the Flume Avro RPC source port is running
    Log4jAppender appender = new Log4jAppender();
    appender.setName("flume");
    appender.setHostname("localhost");
    appender.setPort(41415);
    appender.setDatasetRepositoryUri("repo:hdfs:/tmp/data");
    appender.setDatasetName("events");
    appender.activateOptions();

    Logger.getLogger("org.kitesdk.examples.logging.webapp.LoggingServlet").addAppender(appender);
    Logger.getLogger("org.kitesdk.examples.logging.webapp.LoggingServlet").setLevel(Level.INFO);
  }

  @Test
  public void test() throws Exception {
    run(new CreateDataset());
    for (int i = 1; i <= 10; i++) {
      get("http://localhost:8080/logging-webapp/send?message=Hello-" + i);
    }
    Thread.sleep(40000); // wait for events to be flushed to HDFS
    run(containsString("{\"id\": 10, \"message\": \"Hello-10\"}"), new ReadDataset());
    run(new DeleteDataset());
  }

  @After
  public void tearDown() throws Exception {
    stopTomcat();
  }

  private void startTomcat() throws Exception {
    String appBase = "target/wars/logging-webapp.war";
    tomcat = new Tomcat();
    tomcat.setPort(8080);

    tomcat.setBaseDir(".");
    tomcat.getHost().setAppBase(".");

    String contextPath = "/logging-webapp";

    StandardServer server = (StandardServer) tomcat.getServer();
    server.addLifecycleListener(new AprLifecycleListener());

    tomcat.addWebapp(contextPath, appBase);
    tomcat.start();
  }

  private void stopTomcat() throws Exception {
    if (tomcat != null) {
      tomcat.stop();
    }
  }

  private void get(String url) throws IOException {
    assertThat(Request.Get(url).execute().returnResponse().getStatusLine()
        .getStatusCode(), equalTo(200));
  }

}
