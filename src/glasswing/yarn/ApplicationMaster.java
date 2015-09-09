package glasswing.yarn;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.GnuParser; 
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.NMClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.Records;


public class ApplicationMaster {

  public static void main(String[] args) throws Exception {
	  /*
	  Options gnuOptions = new Options();  
	
	gnuOptions.addOption("glasswingrootlocation", true, "Glasswing root location needed to find python script(local only)");
	gnuOptions.addOption("glasswingapplocationbinary", true, "Glasswing application executable (local only)");
	gnuOptions.addOption("size", true, "Number of workers to be launched (excluding master)");  
	  
	
	CommandLineParser cmdLineGnuParser = new GnuParser();	CommandLine commandLine;;
    try {
        // parse the command line arguments
        commandLine = cmdLineGnuParser.parse(gnuOptions, args);  
    }
    catch( ParseException exp ) {
        // oops, something went wrong
    	System.err.println( "Parsing failed.  Reason: " + exp.getMessage() );
    	return;
    }
    
   	final Integer size = Integer.valueOf(commandLine.getOptionValue("size"));
   	final Integer newsize = size - 1;
	String glasswingapplocationbinary =  commandLine.getOptionValue("glasswingapplocationbinary");
	String glasswingrootlocation =  commandLine.getOptionValue("glasswingrootlocation");
	*/
	  
	  
	List<String> newargs = new ArrayList<String>();
	String glasswingapplocationbinary = null;
	String glasswingrootlocation = null;
	Integer newsize = null, size = null;
    Pattern attributeValuePair = Pattern.compile("--(.*)=(.*)");
    Pattern onlyAttribute  = Pattern.compile("--(.*)");
    
	for (String arg : args ) {
	    Matcher matcher = attributeValuePair.matcher(arg);
	    if(matcher.find())
		switch(matcher.group(1)){
			case "yarn.glasswingapplocationbinary" : 
				glasswingapplocationbinary = matcher.group(2);
				break;
				
			case "yarn.glasswingrootlocation" : 
				glasswingrootlocation = matcher.group(2);
				break;	
			  
			case "net.size" : 
				size = Integer.parseInt(matcher.group(2));
				newsize = size - 1;
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
	    		System.out.println("[Application Master] Bad formated arg" + arg);
	    }
	}

	if(glasswingapplocationbinary == null || glasswingrootlocation == null || newsize == null)
	{
		System.out.println("yarn.glasswingapplocationbinary or yarn.glasswingrootlocation or net.size arguments missing");
		return;
	}
	
	
	String command = "python " + glasswingrootlocation + "/scripts/run-with-classpath.py ";
	command += glasswingapplocationbinary + " ";
	command += StringUtils.join(" ", newargs);

	
	System.out.println("[ApplicationMaster] Tentative line command: " + command); 
	  
    // Initialize clients to ResourceManager and NodeManagers
    Configuration conf = new YarnConfiguration();

    AMRMClient<ContainerRequest> rmClient = AMRMClient.createAMRMClient();
    rmClient.init(conf);
    rmClient.start();

    NMClient nmClient = NMClient.createNMClient();
    nmClient.init(conf);
    nmClient.start();

    // Register with ResourceManager
    System.out.println("registerApplicationMaster");
    rmClient.registerApplicationMaster("", 0, "");

    // Priority for worker containers - priorities are intra-application
    Priority priority = Records.newRecord(Priority.class);
    priority.setPriority(0);

    // Resource requirements for worker containers
    Resource capability = Records.newRecord(Resource.class);
    capability.setMemory(128);
    capability.setVirtualCores(1); 
    
    // Make container requests to ResourceManager
    for (int i = 0; i < size; ++i) {
      ContainerRequest containerAsk = new ContainerRequest(capability, null, null, priority);
     
      System.out.println("Creating ContainerRequest " + i);
      rmClient.addContainerRequest(containerAsk);
    }

    // Obtain allocated containers, launch and check for responses
    int responseId = 0;
    int completedContainers = 0;
    Boolean masterContainerLunched = false;
    String GlasswingMasterip = "",individualCommand ="" ;
    while (completedContainers < size) {
        AllocateResponse response = rmClient.allocate(responseId++);
        for (Container container : response.getAllocatedContainers()) {
            // Launch container by create ContainerLaunchContext
            ContainerLaunchContext ctx = Records.newRecord(ContainerLaunchContext.class);
            
            if(!masterContainerLunched){	
            	GlasswingMasterip = InetAddress.getByName(container.getNodeId().getHost()).getHostAddress();
            	individualCommand  = command + " --net.master-address=" + GlasswingMasterip + " --net.master-port=5566 --net.interface=br0 --net.size=" + newsize + "  --net.master=1";
            	masterContainerLunched = true;
            }
            else{
            	individualCommand  = command + " --net.master-address=" + GlasswingMasterip  + "  --net.master-port=5566 --net.interface=br0 --net.size=" + newsize + " --net.master=0";
            }
            

            ArrayList<String> commands = new ArrayList<String>();  
            commands.add("module unload gcc boost opencl;");
            commands.add("module add opencl-amd/2.9 gcc/4.9.0 boost/1.54.0 python/2.7.9 hadoop/2.5.0;");
            commands.add(individualCommand   +   
					        		" 1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout" +
		                            " 2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr;");
            ctx.setCommands(commands); 
            /*
            ctx.setCommands(
					        Collections.singletonList(
					        		command   +   
					        		" 1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout" +
		                            " 2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr"
					        ));
            */
            
            System.out.println("Launching container " + container.getId() + " running on " + container.getNodeId().getHost()+ " node " + "with command: " + individualCommand + " logs location: " + ApplicationConstants.LOG_DIR_EXPANSION_VAR);
            nmClient.startContainer(container, ctx);
        }
        for (ContainerStatus status : response.getCompletedContainersStatuses()) {
            ++completedContainers;
            System.out.println("Completed container " + status.getContainerId());
        }
        Thread.sleep(100);
    }

    // Un-register with ResourceManager
    rmClient.unregisterApplicationMaster(
        FinalApplicationStatus.SUCCEEDED, "", "");
  }
}
