#Thesis Project - Ion Bara
##Glasswing YARN application

The project contains an eclipse project with:
* Source code
* Ant scripts for deployment

Usage:
======


##Managed Mode
Run glasswing on YARN by specifing all parameters AND:
* --yarn.glasswingapplocationbinary (location of app binary)
* --yarn.glasswingrootlocation (location of glasswing root folder - needed to access python script atm)

Example:

$ hadoop jar /cm/shared/package/hadoop/hadoop-2.5.0/share/hadoop/yarn/hadoop-yarn-applications-unmanaged-am-launcher-2.5.0.jar Client -classpath /home/iba340/glasswing-yarn.jar -cmd 'java glasswing.yarn.ApplicationMaster --yarn.glasswingapplocationbinary=/home/iba340/glasswing/build/apps/wordcount/wc --yarn.glasswingrootlocation=/home/iba340/glasswing --net.size=3 --cl.code-file=/home/iba340/glasswing/apps/wordcount/wc.cl --cl.device=Intel --cl.platform=AMD --io.input=/etc/passwd --io.inter-dir=./inter --io.output=/home/iba340/output'
<<<<<<< HEAD



 
=======
>>>>>>> 5c716dd30032ce70b80ea91349632a97b736fe21
