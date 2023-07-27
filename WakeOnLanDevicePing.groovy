/*
 *	Wake On Lan w/ Ping
 *
 *	Adapted from: Ramdev Shallem
 * 
 */

metadata {
    definition (name: "WakeOnLan Device w/ Ping", namespace: "jake", author: "jake") {
        capability "Switch"
        capability "Refresh"
        command "wake"
        
        attribute  "wolStatus", "enum", [ "starting", "failed", "success", "unknown" ]
    }

    preferences {
        input(name:"myMac", type: "text", required: true, title: "MAC of workstation")
        input(name:"mySecureOn", type: "text", required: false, title: "SecureOn", description:"Certain NICs support a security feature called \"SecureOn\". It allows users to store within the NIC a hexadecimal password of 6 bytes. Example: \"EF4F34A2C43F\"")
        input(name:"myIP", type: "text", required: false, title: "IP Address", description:"Use this for accessing remote computers outside the local LAN. If not entered, will send the packet to all the devices inside the LAN (255.255.255.255)")
        input(name:"myPort", type: "number", required: false, title: "Port", description:"Default: 7", defaultValue :"7")
        input(name:"myDeviceIp", type: "text", required: false, title: "Device IP", description:"IP of the device")
        input(name:"pingWol", type: "number", required: false, title: "Ping device up to X times w/ 1s pause after WOL call", description:"Default: 0 / disabled", defaultValue : "0")
        input(name:"pingScheduleMinutes", type: "number", required: false, title: "Ping every N minutes",description:"Default: 0 / disabled", defaultValue : "0")
    }
    
}

def on() {
   wake()
}

def off() {
    sendEvent(name: 'switch', value: "off")
}

def refresh() {
    setDeviceStateByPing()
}

def setDeviceStateByPing() {
    if (myDeviceIp) {
        pingResult = sendPing(myDeviceIp, 3)
        if (pingResult.success > 0) {
            sendEvent(name: 'switch', value: "on")
        } else {
            sendEvent(name: 'switch', value: "off")
        }
    }
}

// https://community.hubitat.com/t/2-2-7-icmp-ping-for-apps-and-drivers/70610
def sendPing(ip, count = 3) {
	hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(ip, count)
	def success = "nullResults"
	def minTime = "n/a"
	def maxTime = "n/a"
	if (pingData) {
		success = (100 * pingData.packetsReceived.toInteger() / count).toInteger()
		minTime = pingData.rttMin
		maxTime = pingData.rttMax
	}
	def pingResult = [ip: ip, min: minTime, max: maxTime, success: success, transmitted: pingData.packetsTransmitted, received: pingData.packetsReceived, loss: pingData.packetLoss]
	return pingResult
}

def wake() {
    def secureOn = mySecureOn ?: "000000000000"
    def port = myPort ?: 7
    def ip = myIP ?: "255.255.255.255"
    def macHEX = myMac.replaceAll("-","").replaceAll(":","").replaceAll(" ","")
    def command = "FFFFFFFFFFFF$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$macHEX$secureOn"
    def myHubAction = new hubitat.device.HubAction(command, 
                           hubitat.device.Protocol.LAN, 
                           [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, 
                            destinationAddress: "$ip:$port",
                            encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
    sendHubCommand(myHubAction)
    log.info "Sent WOL to $myMac"
    //log.debug "Sent magic packet $command to $ip:$port"
        
    if(pingWol > 0 && myDeviceIp) {
        sendEvent(name: 'wolStatus', value: "starting")
        
        def attempts = 0
        def successful = false
        while (attempts < pingWol) {
            def results = sendPing(myDeviceIp, 2)
            attempts++
            if (results.success > 0) {
                successful = true
                break
            }            
            pauseExecution(pingWolSeconds * 1000)            
        }
        
        sendEvent(name: 'wolStatus', value: successful ? "success" : "failed")
    } else {
        sendEvent(name: 'wolStatus', value: "unknown")
        sendEvent(name: 'switch', value: "off")
    }
}

def updated() {
    device.updateDataValue("MAC",myMac)
    device.updateDataValue("SecureOn",mySecureOn)
    device.updateDataValue("IP",myIP)
    device.updateDataValue("Port",myPort ? "$myPort":"")
    
    scheduleTasks()
}

def scheduleTasks() {
    unschedule()
    if (pingScheduleMinutes > 0 && myDeviceIp) {
        Random rnd = new Random()
        schedule( "${rnd.nextInt(59)} */${ pingScheduleMinutes } * ? * *", "refresh" )
    }
}

def configure(){
    scheduleTasks()
}
