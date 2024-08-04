/**
 *  BOND Motorized Shade
 *
 *  Copyright 2019-2020 Dominick Meglio
 *
 */

import java.util.concurrent.*;
import groovy.transform.Field

@Field static ConcurrentHashMap<String,BigInteger> lastOpenCloseStartTime = new ConcurrentHashMap<String,BigInteger>()

metadata {
    definition (
		name: "BOND Motorized Shade - v3", 
		namespace: "bond", 
		author: "dmeglio@gmail.com",
		importUrl: "https://raw.githubusercontent.com/dcmeglio/hubitat-bond/master/drivers/BOND_Motorized_Shade.groovy",
        singleThreaded: true
	) {
        capability "WindowShade"
		capability "Switch"
		
        command "stop"
        command "fixShadeState", [[name:"Shade*", type: "ENUM", description: "Shade", constraints: ["opening", "partially open", "closed", "open", "closing", "unknown"] ], 
                                 [name:"Position", type: "NUMBER", description: "Position", constraints: 0..100]]
		command "toggle"
        command "presetPosition"
    }
}
preferences {
    input("swapOpenClose", "bool", title: "Swap Open and Close Commands", defaultValue: false)
	input("responseTimeMs", "number", title: "The time it takes for the shade to respond in ms", defaultValue:0)
	input("closeTimeMs", "number", title: "The time it takes to close in ms", defaultValue:0)
	input("openTimeMs", "number", title: "The time it takes to open in ms", defaultValue:0)
	input("retryCommand", "bool", title: "Send commands twice", defaultValue:false)
}

def recordOpenCloseStart() {
    def currentState = device.currentValue("windowShade", true)
    def lastRecorded = getLastOpenCloseStartTime() ?: 0
    def currentTime = new Date().getTime()
    def lastDuration = currentTime - lastRecorded

    // if we're currently opening/closing, then we're already moving so don't update date
    if ((currentState != "opening" && currentState != "closing") || lastRecorded == 0 || lastDuration >= 120000) {
        //log.info "Updated state time: ${currentState}, ${lastRecorded}, ${currentTime}, ${lastDuration}"
        updateLastOpenCloseStartTime(currentTime)
    }
    else {
        //log.info "Did not update state time: ${currentState}, ${lastRecorded}, ${currentTime}, ${lastDuration}"
    }
}

def open() {
    unscheduleJobs()
	swapOpenClose ? parent.handleClose(device, false) : parent.handleOpen(device, false)
    recordOpenCloseStart()
    if(retryCommand) {
        pauseExecution(10)
        swapOpenClose ? parent.handleClose(device, false) : parent.handleOpen(device, false)
    }
    
    def changeTimeMs = 10000
    
    if (openTimeMs > 0) {
        def currentPosition = device.currentValue("position", true) >= 0 ? device.currentValue("position", true) : 0
        changeTimeMs = calculatePositionChangeMs(currentPosition, 100)
        if (changeTimeMs > (100 + responseTimeMs))
        {
            sendEvent(name: "windowShade", value: "opening")
        }
    }
    
    // TODO: sometimes this can run before the scheduled stop()
    runInMillis(changeTimeMs, 'openFinish')
}

def presetPosition() {
    parent.handlePreset(device)   
}

def openFinish() {
    sendEvent(name: 'position', value: 100)
    sendEvent(name: "windowShade", value: "open")
    sendEvent(name: "switch", value: "on")
}

def close() {
    unscheduleJobs()
	swapOpenClose ? parent.handleOpen(device, false) : parent.handleClose(device, false)
    recordOpenCloseStart()
    if(retryCommand) {
        pauseExecution(10)
        swapOpenClose ? parent.handleOpen(device, false) : parent.handleClose(device, false)
    }
    def lastPosition = getLastPosition()
    
    def changeTimeMs = 10000
    
    if (closeTimeMs > 0) {
        def currentPosition = lastPosition >= 0 ? lastPosition : 0
        changeTimeMs = calculatePositionChangeMs(currentPosition, 0)
        if (changeTimeMs > (100 + responseTimeMs))
        {
            sendEvent(name: "windowShade", value: "closing")
        }
    }
    
    // TODO: sometimes this can run before the scheduled stop()
    runInMillis(changeTimeMs, 'closeFinish')
}

def closeFinish() {
    sendEvent(name: 'position', value: 0)
    sendEvent(name: "windowShade", value: "closed")
    sendEvent(name: "switch", value: "off")
}

def on() {
	open()
}

def off() {
	close()
}

def toggle() {
	if (device.currentValue("windowShade", true) == "open")
		close()
	else
		open()
}

def getLastOpenCloseStartTime() {
    def deviceId = device.getId()
    def value = lastOpenCloseStartTime.get(deviceId) as BigInteger
    if (value) {
        return value
    }
}

def updateLastOpenCloseStartTime(updatedTime) {
    def deviceId = device.getId()
    if (updatedTime) {
        lastOpenCloseStartTime.put(deviceId, updatedTime)
    }
    else {
        lastOpenCloseStartTime.put(deviceId, ((new Date()).toTime().toString()))
    }
}

def unscheduleJobs() {
    //log.debug "unscheduling jobs"
    unschedule('stop')
    unschedule('closeFinish')
    unschedule('openFinish')
}

def stop() {
    //log.debug "Entered stop"
    // Calculate position and update states
    unscheduleJobs()
    
    def currentState = device.currentValue("windowShade", true)
    def lastPosition = getLastPosition()
    def currentTime = new Date().getTime()
    def lastCommandTime = getLastOpenCloseStartTime()
    
    // Always send the stop command, even if we don't think anything is happening
    parent.handleStop(device)
    if(retryCommand) {
        pauseExecution(5)
        parent.handleStop(device)
    }
    
    // We attempt to calculate the current state of the shade based on the provided preferences
    if (currentState == "opening") {
        def newPosition = getCurrentShadePosition(currentState, lastPosition, lastCommandTime, currentTime)
        def newState = (newPosition == 100) ? "open" : "partially open"
        
        sendEvent(name: 'position', value: newPosition)
        sendEvent(name: "windowShade", value: newState)
        sendEvent(name: "switch", value: "on")
    }
    else if (currentState == "closing") {
        def newPosition = getCurrentShadePosition(currentState, lastPosition, lastCommandTime, currentTime)
        def newState = (newPosition == 0) ? "closed" : "partially open"
        def newSwitchState = (newPosition == 0) ? "off" : "on"
        
        sendEvent(name: 'position', value: newPosition)
        sendEvent(name: "windowShade", value: newState)
        sendEvent(name: "switch", value: newSwitchState)
    }
    else {
        log.warn ("Unable to determine the state on stop, ${currentState}")
    }
}

def getCurrentShadePosition(String direction, Number lastPosition, Number startTime, Number endTime) {
    def timeDiff = (endTime - startTime)
    
    if (direction == "opening") {
        if (openTimeMs > 0) {
            def changedPercent = (timeDiff / (openTimeMs + responseTimeMs)) * 100
            def newPosition = Math.min((lastPosition + changedPercent).toInteger(), 100)
            
            //log.debug("Calculated position from ${lastPosition}%: ${endTime} - ${startTime} = ${timeDiff}ms, ${changedPercent}% => ${newPosition}")

            return newPosition
        }
        else {
            // We assume we opened all the way since we don't know any better
            return 100
        }
    }
    else if (direction == "closing") {
        if (closeTimeMs > 0) {
            def changedPercent = (timeDiff / (closeTimeMs + responseTimeMs)) * 100
            def newPosition = Math.max((lastPosition - changedPercent).toInteger(), 0)
            
            //log.info("Calculated position from ${lastPosition}%: ${endTime} - ${startTime} = ${timeDiff}ms, ${changedPercent}% => ${newPosition}")

            return Math.max(0, newPosition)
        }
        else {
            // We assume we closed all the way since we don't know any better
            return 0
        }
    }
    else {
        return lastPosition >= 0 ? lastPosition : 0
    }
}

def fixShadeState(shade, newPosition) {
	parent.fixShadeState(device, shade)
    if (newPosition) {
        sendEvent(name: 'position', value: newPosition)
    }
}

def startPositionChange(String direction) {
    if (direction == "open") {
        open()
    }
    else if (direction == "close") {
        close()
    }
    else {
        log.warn "Unknown startPositionChange direction: ${direction}"
    }
}

def stopPositionChange() {
    stop()
}

Integer calculatePositionChangeMs(Number currentPosition, Number newPosition) {
    def positionChange = newPosition - currentPosition
    def changeTimeMs = positionChange > 0 ? (Math.abs(openTimeMs * (positionChange / 100)) + responseTimeMs) : (Math.abs(closeTimeMs * (positionChange / 100)) + responseTimeMs)
    def currentState = device.currentValue("windowShade", true)
    
    if (windowShade == "opening" || windowShade == "closing") {
        def runningMillis = ((new Date().getTime()) - getLastOpenCloseStartTime)
        return runningMillis - changeTimeMs.toInteger()
    }
    else {
        return changeTimeMs.toInteger()
    }
}

def getLastPosition() {
    def lastPosition = device.currentValue("position", true)
    if (lastPosition == null) {
        def lastState = device.currentValue("windowShade", true)
        lastPosition = (lastState == "closed" || lastState == "closing") ? 0 : 100
    }
    
    return lastPosition
}

def setPosition(Number newPosition) {
    unscheduleJobs()
    
    if (newPosition == 0) {
        close()
    } else if (newPosition == 100 || newPosition == 99) {
        open()
    } else if (openTimeMs > 0 && closeTimeMs > 0) {
        def lastState = device.currentValue("windowShade", true)
        def lastPosition = getLastPosition()
        def lastRecorded = getLastOpenCloseStartTime() ?: 0
    
        def currentPosition = getCurrentShadePosition(lastState, lastPosition, lastRecorded, (new Date().getTime()))
        def positionChange = newPosition - currentPosition
        def changeTimeMs = calculatePositionChangeMs(currentPosition, newPosition)
    
        //log.debug "Moving to ${newPosition} over ${changeTimeMs}ms"
        
        //["opening", "partially open", "closed", "open", "closing", "unknown"]
        if (positionChange > 0) {
            if (currentState == "closing") {
                // We need to reverse direction
                stop()
            }
            
            if (changeTimeMs < 100 && windowShade == "opening") {
                // Close enough
                stop()
            }
            else {
                if (currentState != "opening") {
                    startPositionChange("open")
                    sendEvent(name: "windowShade", value: "opening")
                }
                runInMillis(changeTimeMs, 'stop')
            }
        }
        else if (positionChange < 0) {
            
            if (currentState == "opening") {
                // We need to reverse direction
                stop()
            }
            
            if (changeTimeMs < 100 && windowShade == "closing") {
                // Close enough
                stop()
            }
            else {
                if (currentState != "closing") {
                    startPositionChange("close")
                    sendEvent(name: "windowShade", value: "closing")
                }
                runInMillis(changeTimeMs, 'stop')
            }            
        }
    }
    else {
        log.warn "no-op for position value " + newPosition + ", set openTimeMs and closeTimeMs"
    }
}
