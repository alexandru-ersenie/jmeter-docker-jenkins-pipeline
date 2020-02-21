tag='5.0.1'
host="${HOST}"
casino="${CASINO}"
agentIpList=''
workspace=''


/* Used for pulling the image. The withRegistry sets the context to retrieve only the docker image, not using the entire
link . That is why we need to perform the pull only using the image name
Yet, when running the container, we need to start it with the complete image name. Therefore we need two variables here
*/

tagged_image=docker.image('performance/docker-edict-jmeter:'+tag)
image=docker.image('myregistry:5000/performance/docker-edict-jmeter:'+tag)

/* We will hold the ip's of the JMeter Agent Containers in a list so we can forward it to the JMeter Master when starting the test
The handleList is used for storing the container handles of the JMeter Agents so we can perform shutdown of Agents
when finishing the test, or cleaning up when something goes wrong */

cIpList = []
cHandleList = []

//Use label to run pipeline only on docker labeled nodes. Set timeout to 60 minutes
timeout(240) {
    node('docker') {

        cleanWs deleteDirs: true, patterns: [[pattern: '*', type: 'INCLUDE']]
        stage('checkout') {
            checkout([$class: 'GitSCM', branches: [[name: "${BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'edictci_gitlab', url: 'git@git.e-dict.net:UnifiedAutomatedDeployment/jmeter-performance-tests.git']]])
            // Read threshold values for testcases from sla.json file
            data = readJSON file: 'sla.json'
        }
        // Change into jmeter subfolder, so we do not mount the entire eoc, but only the performance tests
        dir('jmeter') {
            try {

                docker.withRegistry('https://registry.e-dict.net:5000') {
                    tagged_image.pull()
                    stage('startAgents') {
                        // Start 3 JMeter Agents and retrieve their IP and the container handle. Mount current folder into the container
                        for (i = 0; i < 3; i++) {
                            agent = image.run('-e SLEEP=1 -e JMETER_MODE=AGENT -v $WORKSPACE:/home/jmeter/tests', '')
                            agent_ip = sh(script: "docker inspect -f {{.NetworkSettings.IPAddress}} ${agent.id}", returnStdout: true).trim()
                            cIpList.add(agent_ip)
                            cHandleList.add(agent)
                        }
                        // Store the formatted list of JMeter Agent Ips in a String
                        agentIpList = cIpList.join(",")
                    }
                    // Test to ensure that all applications fit in the configured metaspace
                    // by starting 80 games from the game list
                    // Estimated duration: 15 minutes
                    // Test to ensure that after starting all games, expected response times fit within the defined
                    // slas
                    stage('memoryMetaPlayAllGames') {
                        propertiesMap = [
                                'users': 1,
                                'ramptime': 1,
                                'gameRounds': 5,
                                'host': host,
                                'wallet': host,
                                'casino': casino
                        ]
                        performTest('egb/Tplan_EOC_Play_All_Games_EGB.jmx',"${STAGE_NAME}",setPlanProperties(propertiesMap))
                    }
                    // Test to ensure that we do not have a memory leak in starting/closing games (objects being
                    // released after gamesession ends)
                    // Expected number of games played: 24000
                    // Estimated duration: 15 minutes
                    stage('memoryHeapStartCloseAllGames'){
                        propertiesMap = [
                                'users': 1,
                                'rampup': 1,
                                'gameRounds': 1,
                                'repeatLoop':5,
                                'host': host,
                                'wallet': host,
                                'casino': casino
                        ]
                        performTest('egb/Tplan_EOC_Start_All_Games_EGB.jmx',"${STAGE_NAME}",setPlanProperties(propertiesMap))
                    }

                    // Load Test for measuring KPI'S for a defined throughput of 40 games / second
                    stage('performancePlayMoneyGames'){
                        propertiesMap = [
                                'users': 25,
                                'rampup': 25,
                                'gameRounds': 500,
                                'host': host,
                                'wallet': host,
                                'casino': casino
                        ]
                        performTest('egb/Tplan_EOC_Play_Single_Game_EGB.jmx',"${STAGE_NAME}",setPlanProperties(propertiesMap))
                    }


                    /*
                    // Another way to start a container following the sidecar approach is using the withRun method, which differs
                    // from the "inside" method  insofar that all commands in the inside block are executed outside the container

                    // Agents have been started, we can run the test now on the controller.
                    image.withRun('-v "$PWD"/src/test/plans:/home/jmeter/testplans -v "$PWD"/src/test/reports:/home/jmeter/reports -e SLEEP=10','-n -t /home/jmeter/testplans/demo.jmx -l /home/jmeter/reports/result.jtl -e -o /home/jmeter/reports/output -R'+containerIpList.join(",")) { c ->

                        // The waitUntil method will retry every 0.25 seconds, doubling the retry interval up to a maximum of 15 seconds
                        waitUntil {

                            // Check if the container is still running using the container id returned by the withRun method

                            container_status = sh(script: "docker ps --no-trunc | grep ${c.id} | awk '{print \$1}'", returnStdout: true).toString()
                            if (container_status != "") {
                                echo "container id is:"
                                echo container_status
                                return false
                            }
                            else {
                                echo "Container does not exist anymore"
                                return true
                            }
                        }
                    }
                    */
                    stage('cleanup') {
                        // Handle shutdown of previous started JMeter Agents
                        cleanup(cHandleList)
                        cleanWs deleteDirs: true, patterns: [[pattern: '*', type: 'INCLUDE']]
                    }
                }
            }
            catch (Exception e) {
                // Handle shutdown of previous started JMeter Agents
                cleanup(cHandleList)
                cleanWs deleteDirs: true, patterns: [[pattern: '*', type: 'INCLUDE']]
            }
        }
    }
}


// Method for cleaning started JMeter Agents
def cleanup(containerHandleList) {
    for (i =0; i < containerHandleList.size(); i++) {
        containerHandleList[i].stop()
    }
}

def performTest(testplan,report,propertiesList) {
    image.inside('-e JMETER_MODE=MASTER -v $WORKSPACE:/home/jmeter/tests') {
        sh "jmeter -n -t /home/jmeter/tests/jmeter/testplans/$testplan -l $WORKSPACE/jmeter/${report}.jtl -e -o $WORKSPACE/jmeter/$report -Jsummariser.interval=5 -R$agentIpList $propertiesList"
    }
    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: ''+report, reportFiles: 'index.html', reportName: 'HTML Report '+report, reportTitles: ''])
    perfReport constraints: configureCheckList(report),
            graphType: 'PRT', modeEvaluation: true, modePerformancePerTestCase: true, modeThroughput: true, percentiles: '0,50,90,100', persistConstraintLog: true,
            sourceDataFiles:  report+'.jtl'

}

def configureCheckList(report)
{
    constraintList = []
    // Get constraints from JSON File by looking after the key name = test name (given as variable)
    readConstraints = data.find { it['name'] == report }?.get("constraints")
    println("constraints determined dynamically are:"+readConstraints)
    readConstraints.absolute.each {
        constraintList.add(absolute(escalationLevel: 'WARNING', meteredValue: 'LINE90', operator: 'NOT_GREATER', relatedPerfReport: report + '.jtl', success: false, testCaseBlock: testCase(it.name), value: it.threshold))

    }
    println("my final constraint list: "+constraintList)
    return constraintList
}
def setPlanProperties(propertiesMap)
{
    // Retrieve properties defined in the properties map for each plan, and create a string of properties
    // to be passed to JMeter
    propertiesList="-G"+propertiesMap.collect { k,v -> "$k=$v" }.join(' -G')
    println("property list"+propertiesList)
    return propertiesList
}
