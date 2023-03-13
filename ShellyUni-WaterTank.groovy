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
    }

    preferences {        
        input name: "apiUri", type: "text", title: "API URI", description: "The path to the json API", defaultValue: "http://192.168.1.125/status", required: true
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
    scheduleTasks()
    on()
}

def updated() {
    scheduleTasks()
    on()
}

def setLevel(value, rate = null) {
    logger("setLevel() is read-only and not implemented", "error")
}

private sendTankEvent(voltage) {
    Double voltMin = tankMinVoltage
    Double voltMax = tankMaxVoltage
    Double previousVolts = device.currentValue("volts") ?: -1
    
    Double percent = (((voltage - voltMin) / voltMax) * 100)
    Double gallons = (percent * tankSize) / 100
    
    logger("previousVolt=${previousVolts}, voltMin=${voltMin}, voltMax=${voltMax}, volts=${voltage}, percent=${percent}, gallons=${gallons}", "debug")
    
    Double voltEventMin = previousVolts - voltEventThreshold
    Double voltEventMax = previousVolts + voltEventThreshold
    
    sendEvent(name: 'level',   value: percent.round(2) as Double, unit:"%")
    sendEvent(name: 'volts', value: voltage.round(2) as Double, unit:"v")
    sendEvent(name: 'gallons', value: gallons.round(1))
    sendEvent(name: 'healthStatus', value: "online")
    state.lastUpdate = new Date()
}

def refresh()
{    
    logger("'refresh'", "debug")
        
    Map requestParams = [
        uri: apiUri,
        headers: [
            'Content-Type' : 'application/x-www-form-urlencoded; charset=UTF-8',
            'Accept'       : 'application/json, text/javascript, */*; q=0.01'
        ],
        followRedirects: false
    ]

    try {
        httpGet(requestParams) {
            response ->
                responseData = response.getData()
                logger("Request was successful, $response.status, Body: ${responseData}", "debug")
                state.FailureCount = 0
            
                Double voltage = responseData.adcs[0].voltage
                state.uptime = responseData.uptime
                state.WifiRSSI = responseData.wifi_sta.rssi
            
                logger("Voltage = ${voltage}", "debug")
            
                sendTankEvent(voltage)
        }
    } catch (e) {
        failureCount = (state.FailureCount + 1) ?: 0
        logger("Failure #${failureCount}: $e", "error")
        state.FailureCount = failureCount
        
        if (failureCount > offlineThreshold) {
            sendEvent(name: 'healthStatus', value: "offline")
        }
	}
}

void scheduleTasks()
{
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