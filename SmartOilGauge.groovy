/**
 * IMPORT URL: <TBD>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 * Jake Welch    v1.0    2022-06-02     Initial version
 *
*/

metadata {
    definition (name: "Smart Oil Gauge", namespace: "jake9190", author: "Jake", importUrl: "https://raw.githubusercontent.com/jake9190/hubitatDrivers/main/SmartOilGauge.groovy") {
        capability "Switch"       // For Homebridge compat
        capability "Switch Level" // For Homebridge compat
        capability "Health Check"
        
        attribute  "TankName",           "string"
        attribute  "Battery",            "string"
        attribute  "CurrentTankPercent", "number"
        attribute  "CurrentGallons",     "number"
        attribute  "FillableGallons",    "number"
        attribute  "TankSizeGallons",    "number"
        attribute  "lastTankUpdate",     "string"
        attribute  "lastApiCheck",       "string"
        attribute  "healthStatus", "enum", [ "unknown", "offline", "online" ]
        
        capability "Refresh"
		command    "refreshAuth"
    }
    
    preferences {
        input name: "username", type: "text", title: "Username", description: "Your User Name", required: true
        input name: "password", type: "password", title: "Password", description: "Your password",required: true
        input name: "tokenRefreshInDays", type: "number", title: "Token Expiration", description: "Force Token Refresh After X Days", defaultValue: 7, range: "1..30", required: true
        input name: "refreshHours", type: "number", title: "Refresh Frequency (hours)", description: "Data refresh frequency in hours", defaultValue: 2, range: "0..24", required: true
        input (
            name: "configLoggingLevelIDE",
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

def installed()
{
    logger("Driver installed", "debug")
    on()
}

void updated() {
    logger("updated()", "debug")
    logger("debug logging is: ${debugOutput == true}", "debug")
    on()
    scheduleTasks()
}

// Initialize after hub power cycle to force a poll cycle
void initialize() {
    scheduleTasks()
}

void refresh() {
    logger("'refresh'", "debug")
    
    // TOOD: retry
    getTankStatus()
}

void scheduleTasks()
{
    unschedule()
    
    def Second = ( new Date().format( "s" ) as int )
    Second = ( (Second + 5) % 60 )
    
    def Minute = ( new Date().format( "m" ) as int )
    
    schedule( "${Second} ${Minute} */${ refreshHours } ? * *", "refresh" )
}

/******************************************************/

def getTankStatus()
{
    logger("GetTankStatus()", "debug")
    if (cookieIsValid() == false)
    {
        logger("Refreshing token", "debug")
        if (refreshToken() == false)
        {
            logger("Cannot proceed without a valid cookie", "error")
            return false
        }
    }
    
    def returnSuccess = true
    
    logger("Getting app page", "debug")
    
    Map getParams = [
        uri: "https://app.smartoilgauge.com/app.php",
        headers: [
            'Content-Type'       : 'application/x-www-form-urlencoded; charset=UTF-8',
            'Accept'             : 'application/json, text/javascript, */*; q=0.01',
            'Accept-Encoding'    : 'gzip, deflate, br',
            'Accept-Language'    : 'en-US,en;q=0.9',
            'DNT'                : '1',
            'Origin'             : "https://app.smartoilgauge.com",
            'Referer'            : "https://app.smartoilgauge.com/login.php",
            'Sec-Fetch-Dest'     : "document",
            'Sec-Fetch-Mode'     : "navigate",
            'Sec-Fetch-Site'     : "same-origin",
            'X-Requested-With'   : "XMLHttpRequest",
            'sec-ch-ua'          : "\" Not A;Brand\";v=\"99\", \"Chromium\";v=\"102\", \"Google Chrome\";v=\"102\"",
            'sec-ch-ua-mobile'   : "?0",
            'sec-ch-ua-platform' : "\"Windows\"",
            'User-Agent'         : 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.61 Mobile Safari/537.36',
            'Cookie'             : state.authCookies
        ]
    ]
    
    httpGet(getParams) { response -> 
        responseData = response.getData()
        if (response.status != 200 || responseData.Status)
        {
            logger("Get of app page failed with: ${response}", "debug")
            if (response.status == 401 || response.status == 408 || responseData.Status == 401)
            {
                state.authFailed = true
            }
            
            return false
        }
    }
    
    logger("Calling Ajax API", "info")
    
    Map params = [
        uri: "https://app.smartoilgauge.com/ajax/main_ajax.php",
        headers: [
            'Content-Type'       : 'application/x-www-form-urlencoded; charset=UTF-8',
            'Accept'             : 'application/json, text/javascript, */*; q=0.01',
            'Accept-Encoding'    : 'gzip, deflate, br',
            'Accept-Language'    : 'en-US,en;q=0.9',
            'DNT'                : '1',
            'Origin'             : "https://app.smartoilgauge.com",
            'Referer'            : "https://app.smartoilgauge.com/app.php",
            'Sec-Fetch-Dest'     : "empty",
            'Sec-Fetch-Mode'     : "cors",
            'Sec-Fetch-Site'     : "same-origin",
            'X-Requested-With'   : "XMLHttpRequest",
            'sec-ch-ua'          : "\" Not A;Brand\";v=\"99\", \"Chromium\";v=\"102\", \"Google Chrome\";v=\"102\"",
            'sec-ch-ua-mobile'   : "?0",
            'sec-ch-ua-platform' : "\"Windows\"",
            'User-Agent'         : 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.61 Mobile Safari/537.36',
            'Cookie'             : state.authCookies
        ],
        body: "action=get_tanks_list&tank_id=0"
    ]
    
    httpPost(params) { response -> 
        responseData = response.getData()
        logger("Request Status: $response.status, Body: ${responseData}", "debug")
        
        // API always returns 200 with status code in the response body
        if (responseData.Status == 408 || responseData.Status == 401)
        {
            logger("Login failed: ${responseData}", "warning")
            state.authFailed = true
            returnSuccess = false
        }
        else
        {
            try {        
                if (responseData.result == "ok")
                {
                    logger("Updating state values", "debug")
                    
                    def tankName = responseData.tanks[0].tank_name
                    def tankBattery = responseData.tanks[0].battery
                    def tankCurrGallons = responseData.tanks[0].sensor_gallons.toFloat()
                    def tankFillableGallons = responseData.tanks[0].fillable.toInteger()
                    def tankSizeGallons = responseData.tanks[0].nominal.toInteger()
                    def tankCurrPercent = ((tankCurrGallons / tankFillableGallons) * 100).round(3)
                    def tankLastReading = new Date().parse("yyyy-MM-dd HH:mm:ss", responseData.tanks[0].sensor_rt) // Format: 2022-05-31 07:57:50
                
                    sendEvent(name: 'TankName',           value: tankName)
                    sendEvent(name: 'Battery',            value: tankBattery)
                    sendEvent(name: 'CurrentTankPercent', value: tankCurrPercent as Double, unit:"%")
                    sendEvent(name: 'level',              value: tankCurrPercent as Double, unit:"%")
                    sendEvent(name: 'CurrentGallons',     value: tankCurrGallons as Double)
                    sendEvent(name: 'FillableGallons',    value: tankFillableGallons as Integer)
                    sendEvent(name: 'TankSizeGallons',    value: tankSizeGallons as Integer)
                    sendEvent(name: 'lastTankReadTime',   value: tankLastReading as Date)
                    sendEvent(name: 'lastApiCheck',       value: new Date() as Date)
                    sendEvent(name: 'healthStatus',       value: "online")
                }
                else
                {
                    returnSuccess = false
                    logger("Failed to retrieve status: ${responseData.message}", "error")
                    sendEvent(name: 'healthStatus', value: "offline")  // TODO: Set after multiple failures
                }
            }
            catch(e)
            {
                returnSuccess = false
                logger("getTankStatus() request failed: ${e}", "error")
                sendEvent(name: 'healthStatus', value: "offline")  // TODO: Set after multiple failures
            }
        }
    }
	return returnSuccess
}

/******************************************************/

def cookieIsValid()
{
    logger("Checking token status", "debug")
    
    def lastUpdate = state?.lastTokenUpdate ? new Date().parse("yyyy-MM-dd'T'HH:mm:ss+0000", state.lastTokenUpdate) : new Date()
    def forceRefreshAt = lastUpdate.plus(tokenRefreshInDays.toInteger())
    def authFailed = state?.authFailed ? state.authFailed : "false"
    
    def forceUpdateCheck = (forceRefreshAt > (new Date()))
    def authFailedCheck = (authFailed == "false")
    def result = (forceUpdateCheck && authFailedCheck)
    
    logger("Result: ${result}, lastUpdate: ${lastUpdate}, forceRefreshAt: ${forceRefreshAt}, forceUpdateCheck: ${forceUpdateCheck}, authFailed: ${authFailed}, authFailedCheck = ${authFailedCheck}", "debug")
    
    return result
}

def refreshToken()
{
    if (!login() ) {
        logger("Login attempt failed", "warn")
        if ( !login() ) {
			return false
		}
	}
    
    logger("Finished refreshToken()", "debug")
    return true
}

Map parseCookie(cookie)
{    
    def cookieMap = [:]
    def foundName = false
    
    logger("Parsing cookie: ${cookie}", "debug")
    def cookieParams = cookie.split(';')
    if (cookieParams.size() < 2)
    {
        logger("Cookie format is invalid: ${cookie}", "error")
    }
    
    cookieParams.each { param ->
        def values = param.trim().split("=")
        def name = values[0]
        
        if (foundName == false) {
            cookieMap["name"] = values[0]
            cookieMap["value"] = values[1]
            foundName = true
        }
        else
        {
            switch(values[0].toLowerCase()) {
                case "secure":
                    cookieMap["secure"] = true
                    break;
                 case "max-age":
                    // Not implemented
                    break;
                 case "expires":
                    cookieMap["expires"] = new Date(values[1])
                    break;
                 case "domain":
                    cookieMap["domain"] = values[1]
                    break;
                 case "httponly":
                    cookieMap["httponly"] = true
                    break;
                 case "path":
                    cookieMap["path"] = values[1]
                    break;
                 default: 
                    logger("The cookie param is unknown (${values[0]})", "warn")
                    break;
            }
        }
    }
    
    return cookieMap
}

void refreshAuth() {
    state.clear()
    login()
    logger("Finished auth refresh", "debug")
}

def login() {
    logger("login()", "debug")
	Boolean returnSuccess = true
    
    Map loginTokens = updateLoginTokens()
    if (loginTokens["success"] != true)
    {
        logger("Cannot proceed with login", "error")
    }
    
    def phpsessid = loginTokens["phpCookie"]
    def ccf_nonce = loginTokens["ccf_nonce"]
    def userEscaped = urlEncode(username)
    def passwordEscaped = urlEncode(password)

    Map params = [
        uri: "https://app.smartoilgauge.com/login.php",
        headers: [
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'Accept': 'application/json, text/javascript, */*; q=0.01',
            'Accept-Encoding': 'gzip, deflate, br',
            'Accept-Language': 'en-US,en;q=0.9',
            'DNT': '1',
            'Origin': "https://app.smartoilgauge.com",
            'Referer': "https://app.smartoilgauge.com/login.php",
            'Sec-Fetch-Dest': "empty",
            'Sec-Fetch-Mode': "cors",
            'Sec-Fetch-Site': "same-origin",
            'sec-ch-ua'          : "\" Not A;Brand\";v=\"99\", \"Chromium\";v=\"102\", \"Google Chrome\";v=\"102\"",
            'sec-ch-ua-mobile'   : "?0",
            'sec-ch-ua-platform' : "\"Windows\"",
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.61 Mobile Safari/537.36',
            'Cookie': "PHPSESSID=${phpsessid}"
        ],
        body: "username=${userEscaped}&user_pass=${passwordEscaped}&remember=on&ccf_nonce=${ccf_nonce}",
        followRedirects: false
    ]

    logger("Params: $params.headers $params.body", "debug")
    def sessionCookies = "PHPSESSID=${phpsessid}"
    def cookieCount = 1

    try {
        httpPost(params) {
            response ->
            logger("Request was successful, $response.status, Cookies: ${response.getHeaders('Set-Cookie')}, Body: ${response.getData()}", "debug")

            response.getHeaders('Set-Cookie').each { cookie ->
                logger("Cookie: ${cookie.value}", "debug")
                
                def cookieMap = parseCookie(cookie.value)
                
                if (!cookieMap["expires"] && cookieMap["expires"] < newDate) {
                    logger("Skipping cookie due to expiration: ${cookie}", "debug")
                }
                else
                {
                    logger("Adding cookie to collection: ${cookieMap}", "debug")
                    sessionCookies += "; ${cookieMap["name"]}=${cookieMap["value"]}"
                    cookieCount = cookieCount + 1
                }
            }
            
            if (cookieCount < 2) {
                logger("Number of cookies is fewer than expected: ${cookieCount}", "warn")
			    returnSuccess = false
            }
            
            logger("cookies: ${sessionCookies}", "debug")
        }
    } catch (e) {
        logger("Something went wrong during login: $e", "warn")
        returnSuccess = false
	}
    
    if (returnSuccess)
    {
        logger("Success. Updating cookies in state.", "debug")
        state.authCookies = sessionCookies
        state.lastTokenUpdate = new Date()
        state.authFailed = false
    }
    else
    {
        logger("Login failed, not updating cookies", "error")
    }
        
	return returnSuccess
}

Map updateLoginTokens()
{
    Map loginTokens = [:]
    
    params = [
        uri: "https://app.smartoilgauge.com/login.php",	
        contentType: "text/html"
    ]

    def phpCookie = ""
    def ccf_nonce = ""

    // <input type="hidden" name="ccf_nonce" value="obxxLnjytTPRSCuYBuBs">
    httpGet(params) { response ->
        body = response.getData()
        
        // TODO: Doesn't check for PHPSESSID
        // PHPSESSID=5a9ccd9bc79b229b1902343d7753c4ce; path=/
        response.getHeaders('Set-Cookie').each { cookie ->
            logger("Cookie: ${cookie.value}", "debug")
            def cookieMap = parseCookie(cookie.value)
            phpCookie = cookieMap["value"]
        }

        ccf_nonce = body.'**'.find { html ->
            html.@name == 'ccf_nonce'
        }.@value
    }
    
    if (phpCookie && ccf_nonce)
    {
        loginTokens["ccf_nonce"] = ccf_nonce
        loginTokens["phpCookie"] = phpCookie
        loginTokens["success"] = true
        state.LastLoginTokenUpdate = new Date()
        logger("Login tokens updated: headers: ${authCookie}, nonce: ${ccf_nonce}", "debug")
    }
    else
    {
        loginTokens["success"] = false
        logger("Unable to refresh login tokens", "error")
    }
    
    return loginTokens
}

def on() {
    sendEvent(name: 'switch', value: "on")
}

/******************************************************/

def urlEncode(String) {
    return java.net.URLEncoder.encode(String, "UTF-8")
}

private logger(msg, level = "debug") {

    switch(level) {
        case "error":
            if (state.loggingLevelIDE >= 1) log.error msg
            break
        case "warning":
        case "warn":
            if (state.loggingLevelIDE >= 2) log.warn msg
            break
        case "info":
            if (state.loggingLevelIDE >= 3) log.info msg
            break
        case "debug":
            if (state.loggingLevelIDE >= 4) log.debug msg
            break
        case "trace":
            if (state.loggingLevelIDE >= 5) log.trace msg
            break
        default:
            log.debug msg
            break
    }
}
