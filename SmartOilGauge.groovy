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
 * Jake Welch    v0.1     Initial version
 *
*/

metadata {
    definition (name: "Smart Oil Gauge", namespace: "jake9190", author: "Jake Welch", importUrl: "<TBD>") {
        attribute  "TankName",           "string"
        attribute  "Battery",            "string"
        attribute  "CurrentTankPercent", "number"
        attribute  "CurrentGallons",     "number"
        attribute  "FillableGallons",    "number"
        attribute  "TankSizeGallons",    "number"
        attribute  "lastTankUpdate",     "string"
        attribute  "lastApiCheck",       "string"
        
        capability "Refresh"
		command    "refreshAuth"
    }
    
    preferences {
       input name: "username", type: "text", title: "Username", description: "Your User Name", required: true
       input name: "password", type: "password", title: "Password", description: "Your password",required: true
       input name: "tokenRefreshInDays", type: "number", title: "Token Expiration", description: "Force Token Refresh After X Days", defaultValue: 7, range: "1..30", required: true
       input name: "refreshHours", type: "number", title: "Refresh Frequency (hours)", description: "Data refresh frequency in hours", defaultValue: 2, range: "0..24", required: true
       input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
    }
}

def installed()
{
    log.debug "Driver installed"
}

void refresh() {
    if (debugOutput) log.debug "'refresh'"
    
    getTankStatus()    
    scheduleTasks()
}

// Initialize after hub power cycle to force a poll cycle
void initialize() {
    scheduleTasks()
}

void scheduleTasks()
{
    unschedule()
    
    def Second = ( new Date().format( "s" ) as int )
    Second = ( (Second + 5) % 60 )
    
    def Minute = ( new Date().format( "m" ) as int )
    
    unschedule()
    schedule( "${Second} ${Minute} */${ refreshHours } ? * *", "refresh" )
}

void updated() {
    if (debugOutput) log.debug "updated()"
    if (debugOutput) log.debug "debug logging is: ${debugOutput == true}"
    scheduleTasks()
}

/******************************************************/

def getTankStatus()
{
    log.debug "GetTankStatus()"
    if (cookieIsValid() == false)
    {
        log.debug "Refreshing token"
        if (refreshToken() == false)
        {
            log.error "Cannot proceed without a valid cookie"
            return
        }
    }
    
    def returnSuccess = true
    log.info "getTankStatus()"
    Map params = [
        uri: "https://app.smartoilgauge.com/ajax/main_ajax.php",
        headers: [
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'Accept': 'application/json, text/javascript, */*; q=0.01',
            'Accept-Encoding': 'gzip, deflate, br',
            'Accept-Language': 'en-US,en;q=0.9',
            'DNT': '1',
            'Origin': "https://app.smartoilgauge.com",
            'Referer': "https://app.smartoilgauge.com/app.php",
            'Sec-Fetch-Dest': "empty",
            'Sec-Fetch-Mode': "cors",
            'Sec-Fetch-Site': "same-origin",
            'X-Requested-With' : "XMLHttpRequest",
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.61 Mobile Safari/537.36',
            'Cookie': state.authCookies
        ],
        body: "action=get_tanks_list&tank_id=0"
    ]
    
    httpPost(params) { response -> 
        responseData = response.getData()
        if (debugOutput) log.debug "Request Status: $response.status, Body: ${responseData}"
        
        // API always returns 200 with status code in the response body
        if (responseData.status == 408 || responseData.status == 401)
        {
            log.warn "Login failed: ${responseData.message}"
            state.authFailed = true
            returnSuccess = false
        }
        else
        {
            try {        
                if (responseData.result == "ok")
                {
                    def tankName = responseData.tanks[0].tank_name
                    def tankBattery = responseData.tanks[0].battery
                    def tankCurrGallons = responseData.tanks[0].sensor_gallons.toFloat()
                    def tankFillableGallons = responseData.tanks[0].fillable.toInteger()
                    def tankSizeGallons = responseData.tanks[0].nominal.toInteger()
                    def tankCurrPercent = ((tankCurrGallons / tankFillableGallons) * 100).round(3)
                    def tankLastReading = responseData.tanks[0].sensor_rt // new Date().parse("yyyy-MM-dd HH:mm:ss", status.tanks[0].sensor_rt) // Format: 2022-05-31 07:57:50
                
                    sendEvent(name: 'TankName', value: tankName)
                    sendEvent(name: 'Battery', value: tankBattery)
                    sendEvent(name: 'CurrentTankPercent', value: tankCurrPercent, unit:"%")
                    sendEvent(name: 'CurrentGallons', value: tankCurrGallons)
                    sendEvent(name: 'FillableGallons', value: tankFillableGallons)
                    sendEvent(name: 'TankSizeGallons', value: tankSizeGallons)
                    sendEvent(name: 'lastReadTime', value: tankLastReading)
                    
                    lastApiCheck = new Date()
                }
                else
                {
                    returnSuccess = false
                    log.error "Failed to retrieve status: ${responseData.message}"   
                }
            }
            catch(e)
            {
                returnSuccess = false
                log.error "getTankStatus() request failed: ${e}"
            }
        }
    }
	return returnSuccess
}

/******************************************************/

def cookieIsValid()
{
    log.debug "Checking token status"
    
    def lastUpdate = state?.lastTokenUpdate ? new Date().parse("yyyy-MM-dd'T'HH:mm:ss+0000", state.lastTokenUpdate) : new Date()
    def forceRefreshAt = lastUpdate.plus(tokenRefreshInDays.toInteger())
    def authFailed = state?.authFailed ? state.authFailed : "false"
    
    def forceUpdateCheck = (forceRefreshAt > (new Date()))
    def authFailedCheck = (authFailed == "false")
    def result = (forceUpdateCheck && authFailedCheck)
    
    log.debug "Result: ${result}, lastUpdate: ${lastUpdate}, forceRefreshAt: ${forceRefreshAt}, forceUpdateCheck: ${forceUpdateCheck}, authFailed: ${authFailed}, authFailedCheck = ${authFailedCheck}"
    
    return result
}

def refreshToken()
{
    if (!login() ) {
        log.warn "Login attempt failed"
        if ( !login() ) {
			return false
		}
	}
    
    log.debug "Finished refreshToken()"    
    return true
}

Map parseCookie(cookie)
{    
    def cookieMap = [:]
    def foundName = false
    
    log.debug "Parsing cookie: ${cookie}"
    def cookieParams = cookie.split(';')
    if (cookieParams.size() < 2)
    {
        log.error "Cookie format is invalid: ${cookie}"
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
                    log.warning "The cookie param is unknown (${values[0]})"
                    break;
            }
        }
    }
    
    return cookieMap
}

void refreshAuth() {
    state.clear()
    login()
    log.debug "Finished auth refresh"
}

def login() {
    if (debugOutput) log.debug "login()"
	Boolean returnSuccess = true
    
    Map loginTokens = updateLoginTokens()
    if (loginTokens["success"] != true)
    {
        log.error "Cannot proceed with login"
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
            'Origin': "https://app.smartoilgauge.com",
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.61 Mobile Safari/537.36',
            'Cookie': "PHPSESSID=${phpsessid}"
        ],
        body: "username=${userEscaped}&user_pass=${passwordEscaped}&remember=on&ccf_nonce=${ccf_nonce}",
        followRedirects: false
    ]

    log.debug "Params: $params.headers $params.body"
    def sessionCookies = "PHPSESSID=${phpsessid}"
    def cookieCount = 1

    try {
        httpPost(params) {
            response ->
            if (debugOutput) log.debug "Request was successful, $response.status, Cookies: ${response.getHeaders('Set-Cookie')}, Body: ${response.getData()}"

            response.getHeaders('Set-Cookie').each { cookie ->
                log.debug "Cookie: ${cookie.value}"
                
                def cookieMap = parseCookie(cookie.value)
                
                if (!cookieMap["expires"] && cookieMap["expires"] < newDate) {
                    log.debug "Skipping cookie due to expiration: ${cookie}"
                }
                else
                {
                    if (debugOutput) log.debug "Adding cookie to collection: ${cookieMap}"
                    sessionCookies += "; ${cookieMap["name"]}=${cookieMap["value"]}"
                    cookieCount = cookieCount + 1
                }
            }
            
            if (cookieCount < 2) {
                log.warn "Number of cookies is fewer than expected: ${cookieCount}"
			    returnSuccess = false
            }
            
            log.debug "cookies: ${sessionCookies}"
        }
    } catch (e) {
        log.warn "Something went wrong during login: $e"
        returnSuccess = false
	}
    
    if (returnSuccess)
    {
        log.debug "Success. Updating cookies in state."
        state.authCookies = sessionCookies
        state.lastTokenUpdate = new Date()
        state.authFailed = false
    }
    else
    {
        log.error "Login failed, not updating cookies"
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
            log.debug "Cookie: ${cookie.value}"                
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
        log.debug "Login tokens updated: headers: ${authCookie}, nonce: ${ccf_nonce}"  
    }
    else
    {
        loginTokens["success"] = false
        log.error "Unable to refresh login tokens"
    }
    
    return loginTokens
}

/******************************************************/

def urlEncode(String) {
    return java.net.URLEncoder.encode(String, "UTF-8")
}
