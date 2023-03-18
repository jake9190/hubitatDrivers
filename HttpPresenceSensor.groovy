/**
 *  HTTP Presence Sensor v2.0
 *
 *  Copyright 2019 Joel Wetzel
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * v1.0 [Joel Wetzel] Initial version
 * v2.0 [Jake Welch] Add HealthStatus, retries, check intervals, updated http status codes
 *
 */

	
metadata {
	definition (name: "HTTP Presence Sensor - Modified", namespace: "joelwetzel", author: "Joel Wetzel") {
		capability "Refresh"
		capability "Sensor"
		capability "Presence Sensor"
		capability 'Health Check'
        
 		command    "arrived"
 		command    "departed"
        
        attribute  "healthStatus", "enum", [ "unknown", "offline", "online" ]
	} 

	preferences {
		section {
			input (
				type: "string",
				name: "endpointUrl",
				title: "Endpoint URL",
				required: true				
			)
			input (
				type: "number",
				name: "minutes",
				title: "Number of minutes between checks",
				required: true,
                range: "1..59",
				defaultValue: 5
			)
			input (
				type: "number",
				name: "retryCount",
				title: "Number of times to try before marking offline",
				required: true,
				defaultValue: 3
			)
			input (
				type: "bool",
				name: "enableDebugLogging",
				title: "Enable Debug Logging?",
				required: true,
				defaultValue: false
			)
		}
	}
}


def logDebug(msg) {
	if (enableDebugLogging) {
		log.debug msg
	}
}


def installed () {
	log.info "${device.displayName}.installed()"
    updated()
}


def updated () {
	log.info "${device.displayName}.updated()"
    
    state.tryCount = 0    
    unschedule()
    Random rnd = new Random()
    schedule( "${rnd.nextInt(59)} ${rnd.nextInt((int)minutes - 1)}/${ minutes } * ? * *", "refresh" )
}


def refresh() {
	logDebug("${device.displayName}.refresh()")
    
	asynchttpGet("httpGetCallback", [
		uri: endpointUrl,
        timeout: 10
	]);
}

def httpGetCallback(response, data) {
	//log.debug "${device.displayName}: httpGetCallback(response, data)"
    
    statusCode = response.status
    errorMessage = ""
    
    if (response.hasError())
    {
        errorMessage = response.errorMessage
    }
    
    //log.debug "${device.displayName}: ${statusCode}, ${errorMessage}"
	    
	state.tryCount = state.tryCount + 1
    
	if (response == null || response.class != hubitat.scheduling.AsyncResponse) {
        log.info("${device.displayName}: null response or AsyncResponse")
		return
	}
	
	if (statusCode >= 200 && statusCode < 500 && !errorMessage.contains("no route to host") && !errorMessage.contains("timed out") && !errorMessage.contains("unreachable")) {
        logDebug("Presence check succeeded for ${device.displayName}: ${statusCode}, ${errorMessage}")
		state.tryCount = 0
		
		if (device.currentValue('presence') != "present") {
			def descriptionText = "${device.displayName} is ONLINE, ${statusCode}";
			logDebug(descriptionText)
            sendEvent(name: "healthStatus", value: "online")
			sendEvent(name: "presence", value: "present", linkText: deviceName, descriptionText: descriptionText)
		}
	}
    else
    {        
        log.warn("Presence check failed for ${device.displayName}: ${statusCode}, ${errorMessage}, retry count: ${state.tryCount}")
        
    }
    
    if (state.tryCount > retryCount && device.currentValue('presence') != "not present") {
        def descriptionText = "${device.displayName} is OFFLINE";
        logDebug(descriptionText)
        sendEvent(name: "healthStatus", value: "offline")
        sendEvent(name: "presence", value: "not present", linkText: deviceName, descriptionText: descriptionText)
    }
    
    state.lastUpdate = new Date()
}

def arrived() {
    sendEvent(name: "healthStatus", value: "online")
    sendEvent(name: "presence", value: "present", linkText: deviceName, descriptionText: descriptionText)
	logDebug("${device.displayName} has arrived")
}

def departed() {
    sendEvent(name: "healthStatus", value: "online")
    sendEvent(name: "presence", value: "not present", linkText: deviceName, descriptionText: descriptionText)
	logDebug("${device.displayName} has departed")
}
