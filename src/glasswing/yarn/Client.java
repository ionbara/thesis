package glasswing.yarn;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.Apps;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;


public class Client {

	  Configuration conf = new YarnConfiguration();
	  
	  public void run(String[] args) throws Exception {
	    String command = null;
	    Path jarPath = null;
	    	    		
		List<String> newargs = new ArrayList<String>();
	    Pattern attributeValuePair = Pattern.compile("--(.*)=(.*)");
	    Pattern onlyAttribute  = Pattern.compile("--(.*)");
	    
		for (String arg : args ) {
		    Matcher matcher = attributeValuePair.matcher(arg);
		    if(matcher.find())
			switch(matcher.group(1)){
				case "yarn.jarpath" : 
					jarPath = new Path(matcher.group(2));
					break;
										
				default :
					newargs.add(arg);
					break;
			}
		    else {
		    	Matcher matcher2 = onlyAttribute.matcher(arg);
		    	if(matcher2.find())
		    		newargs.add(arg);
		    	else
		    		System.out.println("[Application Master] Bad formated arg " + arg);
		    }
		}

		if(jarPath == null)
		{
			System.out.println("yarn.jarpath not specified");
			return;
		}
		
		command = StringUtils.join(" ", newargs);
		
	    // Create yarnClient
	    YarnConfiguration conf = new YarnConfiguration();
	    YarnClient yarnClient = YarnClient.createYarnClient();
	    yarnClient.init(conf);
	    yarnClient.start();
	    
	    FileSystem fs = FileSystem.get(conf);

	    // Create application via yarnClient
	    YarnClientApplication app = yarnClient.createApplication();

	    // Set up the container launch context for the application master
	    ContainerLaunchContext amContainer = Records.newRecord(ContainerLaunchContext.class);
	    amContainer.setCommands(
						        Collections.singletonList(
						            "$JAVA_HOME/bin/java" +
						            " -Xmx256M" +
						            " glasswing.yarn.ApplicationMaster" +
						            " " + command +
						            " 1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout" + 
						            " 2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr" 
						            )
						        );
	    
	    // Setup jar for ApplicationMaster
	    LocalResource appMasterJar = Records.newRecord(LocalResource.class);
	    setupAppMasterJar(jarPath, appMasterJar);
	    amContainer.setLocalResources(Collections.singletonMap("glasswing-yarn.jar", appMasterJar));

	    // Setup CLASSPATH for ApplicationMaster
	    Map<String, String> appMasterEnv = new HashMap<String, String>();
	    setupAppMasterEnv(appMasterEnv);
	    amContainer.setEnvironment(appMasterEnv);
	    
	    // Set up resource type requirements for ApplicationMaster
	    Resource capability = Records.newRecord(Resource.class);
	    capability.setMemory(256);
	    capability.setVirtualCores(1);
	    
	    if (UserGroupInformation.isSecurityEnabled()) {
	    	  // Note: Credentials class is marked as LimitedPrivate for HDFS and MapReduce
	    	  Credentials credentials = new Credentials();
	    	  String tokenRenewer = conf.get(YarnConfiguration.RM_PRINCIPAL);
	    	  if (tokenRenewer == null || tokenRenewer.length() == 0) {
	    	    throw new IOException(
	    	      "Can't get Master Kerberos principal for the RM to use as renewer");
	    	  }

	    	  // For now, only getting tokens for the default file-system.
	    	  final Token<?> tokens[] =
	    	      fs.addDelegationTokens(tokenRenewer, credentials);
	    	  if (tokens != null) {
	    	    for (Token<?> token : tokens) {
	    	    	System.out.println("Got dt for " + fs.getUri() + "; " + token);
	    	    }
	    	  }
	    	  DataOutputBuffer dob = new DataOutputBuffer();
	    	  credentials.writeTokenStorageToStream(dob);
	    	  ByteBuffer fsTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
	    	  amContainer.setTokens(fsTokens);
	    	}
	    

	    // Finally, set-up ApplicationSubmissionContext for the application
	    ApplicationSubmissionContext appContext = 
	    app.getApplicationSubmissionContext();
	    appContext.setApplicationName("Glasswing YARN Launcher"); // application name
	    appContext.setAMContainerSpec(amContainer);
	    appContext.setResource(capability);
	    appContext.setQueue("default"); // queue 

	    // Submit application
	    ApplicationId appId = appContext.getApplicationId();
	    System.out.println("Submitting application " + appId);
	    yarnClient.submitApplication(appContext);
	    
	    ApplicationReport appReport = yarnClient.getApplicationReport(appId);
	    YarnApplicationState appState = appReport.getYarnApplicationState();
	    while (appState != YarnApplicationState.FINISHED && 
	           appState != YarnApplicationState.KILLED && 
	           appState != YarnApplicationState.FAILED) {
	      Thread.sleep(100);
	      appReport = yarnClient.getApplicationReport(appId);
	      appState = appReport.getYarnApplicationState();
	    }
	    
	    System.out.println(
	        "Application " + appId + " finished with" +
	    		" state " + appState + 
	    		" at " + appReport.getFinishTime());

	  }
	  
	  private void setupAppMasterJar(Path jarPath, LocalResource appMasterJar) throws IOException {
	    FileStatus jarStat = FileSystem.get(conf).getFileStatus(jarPath);
	    appMasterJar.setResource(ConverterUtils.getYarnUrlFromPath(jarPath));
	    appMasterJar.setSize(jarStat.getLen());
	    appMasterJar.setTimestamp(jarStat.getModificationTime());
	    appMasterJar.setType(LocalResourceType.FILE);
	    appMasterJar.setVisibility(LocalResourceVisibility.PUBLIC);
	  }
	  
	  private void setupAppMasterEnv(Map<String, String> appMasterEnv) {
	    for (String c : conf.getStrings(
	        YarnConfiguration.YARN_APPLICATION_CLASSPATH,
	        YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH)) {
	      Apps.addToEnvironment(appMasterEnv, Environment.CLASSPATH.name(),
	          c.trim());
	    }
	    Apps.addToEnvironment(appMasterEnv,
	        Environment.CLASSPATH.name(),
	        Environment.PWD.$() + File.separator + "*");
	  }
	  
	  public static void main(String[] args) throws Exception {
	    Client c = new Client();
	    c.run(args);
	  }
	}
