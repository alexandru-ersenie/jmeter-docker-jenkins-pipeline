# jmeter-docker-jenkins-pipeline
Running Jmeter Tests in a Jenkins Pipeline using Docker containers. Please check the full detail blog post here:

http://www.tresmundi.com/jenkins-performance-test-pipeline-with-jmeter-and-docker/

# JMeter 4.0 Docker Image

This docker image uses the 4.0 release of JMeter. In order to be able to use it in a dynamically fashion (master or 
agent) , an entrypoint is provided that starts the container in Master or Agent mode, depending on the environment
variable set: JMETER_MODE

## Include files

The edict test framework relies massively on groovy and beanshell scripts, that reside in the testplans tree. This files
need to exist on both master and agent, so we need to mount the entire JMeter Performance Tests folder inside the
container

The test framework needs to know where the included files reside, so include paths have to be provided. Due to a 
limitation in the entrypoint ( options cannot be appended after the entrypoint argument), this properties are being 
added in the jmeter image in form of user.properties, together with other options needed for distributed testing

```
server.rmi.ssl.disable=true
includecontroller.prefix=/opt/jenkins/workspace/jmeter-test/
path=/opt/jenkins/workspace/jmeter-test/jmeter/testplans/includes/scripts/
server.rmi.localport=50000
server_port=1099

```
# Starting JMeter Test locally

Check out the jmeter-performance-tests project from git and dive into "jmeter" folder


You can now run the desired test using the following command:

```
docker run -e JMETER_MODE=MASTER -v $PWD:/opt/jenkins/workspace/jmeter-test/jmeter myregistry:5000/performance/docker-edict-jmeter:4.0.0-1-local-1 jmeter -n -t /opt/jenkins/workspace/jmeter-test/jmeter/testplans/egb/Tplan_EOC_Play_Single_Game_EGB.jmx -l /opt/jenkins/workspace/jmeter-test/jmeter/perf-money.jtl -e -o /opt/jenkins/workspace/jmeter-test/jmeter/perf-money -Jsummariser.interval=5  -Gusers=1 -Gramptime=1 -Ggamerounds=1 -Ghost=area03.astroroyal.com -Gcasino=astroroyal
```

## Description of steps

1. Test Framework containing all JMeter Tests is mounted into the jmeter container. Be sure to be inside the jmeter subfolder : ```-v $PWD:/opt/jenkins/workspace/jmeter-test/jmeter ```
2. Define the path to the testplan to be executed: ```-n -t /opt/jenkins/workspace/jmeter-test/jmeter/testplans/egb/Tplan_EOC_Play_Single_Game_EGB.jmx```
3. Define the path to the testresults file: ```-l /opt/jenkins/workspace/jmeter-test/jmeter/perf-money.jtl```
4. Define the path to the jmeter report: ```-e -o /opt/jenkins/workspace/jmeter-test/jmeter/perf-money```
5. Define the load scenario (number of users, rampup, host): ```-Gusers=1 -Gramptime=1 -Ggamerounds=1 -Ghost=area03.astroroyal.com -Gcasino=astroroyal```

## Interpreting results

The report that results out of step 4 is a html report that can be opened in any browser, containing out of the box performance results 

## Pitfalls

Be sure to remove the jtl file and the report file before running the test a second time, otherwise the test will not start,
instead will report that these files exist and cannot be overwritten

# Using JMeter in the pipeline


## Steps in the pipeline

1. Checkout performance tests
2. Start desired number of JMeter Agent instances on a docker configured Jenkins Node
3. Start JMeter Controller and run desired tests on the previous instantiated JMeter Agents. Each test runs in form of a 
stage
4. Generate unique HTML Performance report for each test run
5. Analyse SLA / Test and generate Performance Trend Report / Test
6. Cleanup framework (stop Controller and JMeter Agents)

## Add new test in the pipeline

In order to add a new test in the pipeline, just add the following block. Be sure to configure the path to the testplan
``` 
stage('mytest') {
                           performTest('egb/Tplan_EOC_Play_Single_Game_EGB.jmx',"${STAGE_NAME}",10,10,10)
                       }
```
This will create a new stage called "mytest", with its corresponding HTML Report and Performance Trend after 
executing the test

## Interpreting results automatically

By using the performance plugin, we can evaluate response times from an SLA perspective, and report the build as passed
or failed, i.e. If  90% of all "Game / Spin " requests are less than defined value, test will pass, otherwise it will 
trigger a warning. 

## Add new SLA for a test

The SLAs are currently defined in the sla.json file residing in the jmeter-performance-tests project. It has the form of
```
      "name": "mytest",
      "loadConfiguration": {
        "users": 10,
        "rampup": 10,
        "gamerounds": 10
      },
      "constraints": {
        "absolute": [
          {
            "name": "Game / Spin, Game / Finish game",
            "threshold": 100
          },
          {
            "name": "Authorize EGB",
            "threshold": 200
          }
        ]
      }
    }
```

Currently there only absolute constraints are implemented, meaning that a test reporting a 90 % line greater than the
configured values will trigger a warning and make the build unstable

A threshold (sla) can be assigned to multiple testcases (See above: spin and finish game have the same threshold)

The SLA List contains entries / test :
```
[
    {
      "name": "perf-money",
      "loadConfiguration": {
        "users": 10,
        "rampup": 10,
        "gamerounds": 10
      },
      "constraints": {
        "absolute": [
          {
            "name": "Game / Spin, Game / Finish game",
            "threshold": 100
          },
          {
            "name": "Authorize EGB",
            "threshold": 200
          }
        ]
      }
    },
    {
      "name": "perf-fun",
      "loadConfiguration": {
        "users": 10,
        "rampup": 10,
        "gamerounds": 10
      },
      "constraints": {
        "absolute": [
          {
            "name": "Game / Spin, Game / Finish game",
            "threshold": 10
          },
          {
            "name": "Authorize EGB",
            "threshold": 400
          }
        ]
      }
    }
  ]
```
