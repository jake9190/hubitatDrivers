/*
 	Water Tank via a Shelly Uni
    
*/

metadata {
    definition (name: "Shelly Uni - Water Tank", namespace: "hubitat", author: "Jake Welch") {
        capability "Switch"       // For Homebridge compat
        capability "Switch Level" // For Homebridge compat

        capability "Refresh"
        capability 'Health Check'
        
        attribute  "volts",      "number"
        attribute  "gallons",    "number"
        attribute  "healthStatus", "enum", [ "unknown", "offline", "online" ]
        
        command "manualSetLevel", [[name:"value",type:"NUMBER", description:"", constraints:["NUMBER"]]]
    }

    preferences {
        input name: "apiUri", type: "text", title: "API URI", description: "The path to the json API", defaultValue: "http://192.168.1.136/status", required: true
        input name: "tankMinVoltage", type: "decimal", title: "Tank Min Voltage", description: "The minimum voltage reading expected", defaultValue: 0, range: "0..10", required: true
        input name: "tankMaxVoltage", type: "decimal", title: "Tank Max Voltage", description: "The maximum voltage reading expected", defaultValue: 6.5, range: "1..10", required: true
        input name: "tankSize", type: "number", title: "Tank Size", description: "The size of the tank in gallons", defaultValue: 142, required: true
        input name: "voltEventThreshold", type: "decimal", title: "Voltage Variance", description: "How much voltage must have changed (+/-) to send an event update", defaultValue: 0.03, required: true
        input name: "refreshInMinutes", type: "number", title: "Refresh Frequency (minutes)", description: "Data refresh frequency in minutes", defaultValue: 2, range: "1..60", required: true
        input name: "offlineThreshold", type: "number", title: "Offline Threshold", description: "Number of failures before placing in offline state", defaultValue: 10, range: "0..200", required: true
        input (
            name: "loggingLevel",
            title: "IDE Live Logging Level:\nMessages with this level and higher will be logged to the IDE.",
            type: "enum",
            options: [
                "0" : "None",
                "1" : "Error",
                "2" : "Warning",
                "3" : "Info",
                "4" : "Debug",
                "5" : "Trace"
            ],
            defaultValue: "3",
            required: false
        )
    }
}

def installed() {
    logger("'installed'", "debug")
    scheduleTasks()
    on()
}

def updated() {
    logger("'updated'", "debug")
    scheduleTasks()
    on()
}

def refresh() {
    logger("'refresh'", "debug")
    UpdateStatus()
}

def UpdateStatus() {
    Map requestParams = [
        uri: apiUri,
        headers: [
            'Content-Type' : 'application/x-www-form-urlencoded; charset=UTF-8',
            'Accept'       : 'application/json, text/javascript, */*; q=0.01'
        ],
        followRedirects: false
    ]
    
    asynchttpGet( "ParseStatusRequest", requestParams)
}

def ParseStatusRequest( resp, data ) {
    def statusCode = resp.getStatus()
    if (statusCode == 200 && resp.data != null) {
        state.clear()
        
        responseData = parseJson( resp.data )
        logger("Request was successful, ${ statusCode }, Body: ${responseData}", "debug")
        state.FailureCount = 0
            
        Double voltage = responseData.adcs[0].voltage
        //state.version = responseData.update.old_version
        //state.currentVersion = responseData.update.new_version
        //state.hasUpdate = responseData.update.has_update
        //state.uptime = responseData.uptime
        //state.WifiRSSI = responseData.wifi_sta.rssi
            
        sendTankEvent(voltage)
    } else {
        failureCount = (state.FailureCount + 1) ?: 0
        state.FailureCount = failureCount
        if (failureCount > offlineThreshold) {
            sendEvent(name: 'healthStatus', value: "offline")
        }
        
        logger("Shelly Uni: ${ statusCode } Failure #${failureCount}: ${ resp }", "error")
    }
}

private sendTankEvent(Double voltage) {
    Double voltMin = tankMinVoltage
    Double voltMax = tankMaxVoltage
    Double previousVolts = device.currentValue("volts") ?: -1
    
    Double percent = (((voltage - voltMin) / voltMax) * 100)
    Double gallons = (percent * tankSize) / 100.0
    
    // TODO: Make this more reliable
    Double voltEventMin = previousVolts - voltEventThreshold
    Double voltEventMax = previousVolts + voltEventThreshold
    
    logger("previousVolt=${previousVolts}, voltMin=${voltMin}, voltMax=${voltMax}, volts=${voltage}, voltEventMin=${voltEventMin}, voltEventMax=${voltEventMax}, percent=${percent}, gallons=${gallons}", "debug")
    
    if (voltage < voltEventMin || voltage > voltEventMax || previousVolts == -1) {
        logger("sending event", "debug")
        
        // Switch is always 'on' to support Homekit, and 0% shows up as 100%. Setting the minimum to 1% to workaround this.
        sendEvent(name: 'level',   value: Math.max(1.0 as Double, percent.round(0) as Double), unit:"%")
        sendEvent(name: 'volts', value: voltage.round(1) as Double, unit:"volts")
        sendEvent(name: 'gallons', value: gallons.round(), unit:"gallons")
    } else {
        logger("Skipped sending event", "debug")
    }
    
    sendEvent(name: 'healthStatus', value: "online")
    state.lastUpdate = new Date()
}

void scheduleTasks() {
    unschedule()
    Random rnd = new Random()
    schedule( "${rnd.nextInt(59)} */${ refreshInMinutes } * ? * *", "refresh" )
}

def on() {
    sendEvent(name: 'switch', value: "on")
}

def off() {
 // The switch should stay on, but we'll toggle to enable triggering events like filling the tank
 sendEvent(name: 'switch', value: "off")
 pauseExecution(500)
 sendEvent(name: 'switch', value: "on")
}

def setLevel(value, rate = null) {
    logger("setLevel() is read-only and not implemented", "error")
}

def manualSetLevel(value, rate = null) {
    sendEvent(name: 'level',   value: value as Double, unit:"%")
}

/******************************************************/

private logger(msg, level = "debug") {
    logLevel = loggingLevel.toInteger()
    switch(level) {
        case "error":
            if (logLevel >= 1) log.error msg
            break
        case "warning":
        case "warn":
            if (logLevel >= 2) log.warn msg
            break
        case "info":
            if (logLevel >= 3) log.info msg
            break
        case "debug":
            if (logLevel >= 4) log.debug msg
            break
        case "trace":
            if (logLevel >= 5) log.trace msg
            break
        default:
            log.debug msg
            break
    }
}
