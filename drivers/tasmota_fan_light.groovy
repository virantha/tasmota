/**

Tasmota Fan with Dimmer driver for Tuya-based wall switches

Based on original code by Gary Milne's Tasmota Sync drivers (see copyright notice below); the original code appeared
to have been written for Sonoff fans, and needed to be simplified and changed for Tuya produced dimmer/fan switches.

    * Alexa capability now works; once the device is added to the Alexa skill in the Hubitat app settings:
        * Say "Alexa, set FAN_NAME to medium" to control the fan
        * Say "Alexa, turn on FAN_NAME" to turn light on off 
        * Not sure yet how to get Alexa to set light dimmer level, or if that is even possible without a separate child device
    * Removed fadeSpeed controls (who uses this?)
    * Removed fanSpeed state var and used "speed" string attribute instead as specified by FanControl capability in Hubitat docs
    * Removed the complicated syncTasmota and HubitatResponse differentiators
        * Now, all requests to the hub are acknowledged by the rules, and handled in syncTasmota, in addition to locally at-switch
          initiated changes
    * speed is now limited to 4 levels supported by the Tuya fan, which is constrained in the device page using an ENUM
    * Configure capability is used now 
    * Used setoption20 and setoption54 to make sure dimmer turns on when level is adjusted (instead of having to send power commands)
    * Misc
        * Fixed formatting
        * Took out overly verbose comments


*  Original attribution:
* 
*  Tasmota Sync Fan with Dimmer
*
*  Copyright 2022 Gary J. Milne
*
*  This program is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation.
*
*
* Copyright 2022 Virantha N. Ekanayake
**/

import groovy.transform.Field
import groovy.json.JsonSlurper
@Field static final Map fanSpeeds = [
    "off": -1,
    "low": 0,
    "medium-low": 1,
    "medium": 2,
    "high": 3,
]

metadata {
		definition (name: "Tasmota Fan with Dimmer for Tuya switch", namespace: "virantha", author: "Virantha Ekanayake", importUrl: "https://raw.githubusercontent.com/virantha/tasmota/main/tasmota_fan_light.groovy", singleThreaded: true )  {
        capability "Switch" 
        capability "SwitchLevel"        
        capability "FanControl"
        capability "Refresh"
        capability "Configuration"
    
        //Internally named variables that must be lower case
        attribute "level", "number"    // Dimmer level
        attribute "speed", "string"    // Fan speed
        
		// Driver status message 
        attribute "Status", "string"  
            
        command "fanOff"
        command "brighter"
        command "dimmer"
        command "initialize"
        command "toggle"
        command "setSpeed", [[name:"speed*", type: "ENUM", description: "speed", constraints: fanSpeeds.keySet() as String[] ] ]

	}
    section("Configure the Inputs"){
			input name: "destIP", type: "text", title: bold(dodgerBlue("Tasmota Device IP Address")), description: italic("The IP address of the Tasmota device."), defaultValue: "192.168.0.X", required:true, displayDuringSetup: true
            input name: "HubIP", type: "text", title: bold(dodgerBlue("Hubitat Hub IP Address")), description: italic("The Hubitat Hub Address. Used by Tasmota rules to send HTTP responses."), defaultValue: "192.168.0.X", required:true, displayDuringSetup: true
            input name: "timeout", type: "number", title: bold("Timeout for Tasmota reponse."), description: italic("Time in ms after which a Transaction is closed by the watchdog and subsequent responses will be ignored. Default 5000ms."), defaultValue: "5000", required:true, displayDuringSetup: false
            input name: "logging_level", type: "number", title: bold("Level of detail displayed in log"), description: italic("Enter log level 0-3. (Default is 0.)"), defaultValue: "0", required:true, displayDuringSetup: false            
            input name: "destPort", type: "text", title: bold("Port"), description: italic("The Tasmota webserver port. Only required if not at the default value of 80."), defaultValue: "80", required:false, displayDuringSetup: true
            input name: "username", type: "text", title: bold("Tasmota Username"), description: italic("Tasmota username is required if configured on the Tasmota device."), required: false, displayDuringSetup: true
          	input name: "password", type: "password", title: bold("Tasmota Password"), description: italic("Tasmota password is required if configured on the Tasmota device."), required: false, displayDuringSetup: true
        }  
}


//Turns the light on
def on() {
    log("Action", "Turn on switch", 0)
    callTasmota("POWER2", "on")
    }
        
//Turns the light off
def off() {
	log("Action", "Turn off switch", 0)
    callTasmota("POWER2", "off")
}

//Toggles the light state
void toggle() {
    log("Action", "Toggle ", 0)
    if (device.currentValue("switch") == "on" ) off()
    else on()
}

//Dimmer control for only Dimmer value.
def setLevel(Dimmer) {
	log ("Action - setLevel1", "Request Dimmer: ${Dimmer}%", 0)
	callTasmota("Dimmer", Dimmer)
}

//This Brighter function increments the brightness of the dimmer setting.
void brighter() {
    log ("Action - brighter", "Increasing brightness", 0)
    callTasmota("DIMMER", "+" )
    log ("brighter", "Exiting", 1)
}

//This Dimmer function increments the brightness of the dimmer setting.
void dimmer() {
    log ("Action - dimmer", "Decreasing brightness", 0)
    callTasmota("DIMMER", "-" )
    log ("dimmer", "Exiting", 1)
}


//Dimmer control for dimmer and fade values.
def setLevel(Dimmer, duration) {
    if (duration < 0) duration = 0
    if (duration > 40) duration = 40
    if (duration > 0 ) duration = Math.round(duration * 2)    //Tasmota uses 0.5 second increments so double it for Tasmota Speed value
    delay = duration * 10 + 5    //Delay is in 1/10 of a second so we make it slightly longer than the actual fade delay.
	log ("Action - setLevel2", "Request Dimmer: ${Dimmer}% ;  SPEED2: ${duration}", 0)
    command = "Rule3 OFF ; Dimmer ${Dimmer} ; SPEED2 ${duration} ; DELAY ${delay} ; Rule3 ON"
	callTasmota("BACKLOG", command)
}

//Turns the fan off.
def fanOff() {
    log("Action", "Turn fan off", 0)
    sendTuyaSpeed("off")
}

// Set the fan speed
def setSpeed(String speed) {
    log("Action", "setSpeed: Requested speed is: ${speed}", 0)
    sendTuyaSpeed(speed)
}

//Cycles the fan to the next position in the cycle Off, Low, Medium, High, Off.
//This is a function name expected to be present when the FanControl capability is enabled.
void cycleSpeed(){
    speeds = fanSpeeds.keySet() as String[] // list of available speeds

    // Find current speed index in list of speeds
    currSpeed = device.currentValue("speed")
    currSpeedIndex = speeds.findIndexOf { name -> name == currSpeed}  
    log("cycleSpeed", "found currSpeed ${currSpeed}, ${currSpeedIndex}", 2)
    
    // Now, go to the next index and set that speed
    newSpeedIndex = (currSpeedIndex + 1) % speeds.size()
    log("cycleSpeed", "found newSpeedIndex ${newSpeedIndex}", 2)
    newSpeed = speeds[newSpeedIndex]
    setSpeed(newSpeed)
}

/* Main function to translate Hubitat fan levels (off, low, medium, etc) into
   Tasmota fan levels (off, 0, 1, 2, 3)

*/
def sendTuyaSpeed(String speed) {
  
    if (speed == 'off') {
        // Turn off fan
        tuyaSendFanOff()
    } else {
        // Convert speed string to a Tuya fan speed int
        newSpeed = fanSpeeds[speed]
        tuyaSendFanSpeed(newSpeed)
    }
}

private void tuyaSendFanOff() {
    // Could also use callTasmota("POWER1", "off")
    callTasmota("TUYASEND", "1,0")
}
private void tuyaSendFanSpeed(fanspeed) {
    callTasmota("TUYASEND4", "3,${fanspeed}")
}

//Sync the UI to the actual status of the device manually. The results come back to the parse function.
def refresh(){
		log ("Action", "Refresh started....", 0)
        state.LastSync = new Date().format('yyyy-MM-dd HH:mm:ss')
		callTasmota("STATUS", "0" )
}

//Updated gets run when the "Initialize" button is clicked or when the device driver is selected
def initialize(){
	log("Initialize", "Device initialized", 0)
	//Make sure we are using the right address
    updateDeviceNetworkID()
    
   	//To be safe these are populated with initial values to prevent a null return if they are used as logic flags
    if ( state.Action == null ) state.Action = "None"
    if ( state.ActionValue == null ) state.ActionValue = "None"
    if ( device.currentValue("Status") == null ) updateStatus("Complete")   
    //if ( device.fanSpeed == null ) sendEvent(name: "fanSpeed", value: 0 )
    if ( device.speed == null ) sendEvent(name: "speed", value: "--" )
     
    //Do a refresh to sync the device driver
    refresh()
}

def configure(){
    configureTasmota()
    log("Configure", "Injecting tasmota Rule 3..", 0)
    log ("Action - tasmotaInjectRule","Injecting Rule3 into Tasmota Host. To verify go to Tasmota console and type: rule 3", 0)
    state.ruleInjection = true
    //Assemble the rule. It is broken up this way for readibility and debugging. 
    rule3 = "ON Power2#State DO backlog0 Var10 %value% ; RuleTimer1 1 ENDON "  // Light power change
    rule3 = rule3 + "ON Power1#State DO backlog0 Var9 %value% ; RuleTimer1 1 ENDON "  // Light power change
    rule3 = rule3 + "ON StatusSNS#Time DO backlog0 Var12 %value% ; RuleTimer1 1 ENDON "  // refresh command from hub to initiate manual status update
    rule3 = rule3 + "ON Dimmer DO backlog0 Var11 %value% ; RuleTimer1 1 ENDON "  // 
    rule3 = rule3 + "ON TuyaReceived#Data=55AA03070005030400010016 do backlog0 Var14 1 ; tuyasend 1,1 ; RuleTimer1 1 ENDON "  // Fan low
    rule3 = rule3 + "ON TuyaReceived#Data=55AA03070005030400010117 do backlog0 Var14 2 ; tuyasend 1,1 ; RuleTimer1 1 ENDON "  // Fan medium-low
    rule3 = rule3 + "ON TuyaReceived#Data=55AA03070005030400010218 do backlog0 Var14 3 ; tuyasend 1,1 ; RuleTimer1 1 ENDON "  // Fan medium
    rule3 = rule3 + "ON TuyaReceived#Data=55AA03070005030400010319 do backlog0 Var14 4 ; tuyasend 1,1 ; RuleTimer1 1 ENDON "  // Fan high
    
    rule3 = rule3 + "ON Rules#Timer=1 DO Var15 %Var10%,%Var11%,%Var12%,%Var13%,%Var14%,%Var9% ENDON "
    //We have to use single quotes here as there is no way to pass a double quote via a URL. We will replace the single quote with a double quote when we get a response back so it can be handled as JSON.
    rule3 = rule3 + "ON Var15#State\$!%Var16% DO backlog ; Var16 %Var15% ; webquery http://" + settings.HubIP + ":39501 POST {'TSync':'True','Switch1':'%Var10%','Dimmer':'%Var11%','FanSpeed':'%Var14%', 'FanSwitch': '%Var9%', 'time':'%Var12%'} ENDON "
    
    callTasmota("RULE3", rule3)
    
    //and then make sure the rule is turned on.
    def parameters = ["BACKLOG","RULE3 ON"]
    //Runs the prepared BACKLOG command after the latest that last command could have finished.
    runInMillis(remainingTime() + 50, "callTasmota", [data:parameters])
}
    

// Try to get all the Tuya setup in, the very first time Tasmota has been flashed onto these devices
// to get the firmware to understand it's a Tuya-based device with fan and dimmer controls.  You may have
// to just run these commands manually in the console of the Tasmota web-ui of each individual switch, as 
// I have not tested this function extensively
def configureTasmota() {
    // First, make sure baud rate is set to 115200 and all capabilities are enabled
	//log ("configureTasmota", "Now enabling fan/dimmer functions", 0)
    //callTasmota("BACKLOG", "TuyaMCU 11,1; TuyaMCU 21,10; TuyaMCU 12,9")
    //pauseExecution(2000)
    callTasmota("module", "54")
    pauseExecution(1000)
	log ("configureTasmota", "Waiting for baud rate ... 1 sec", 0)
    callTasmota("SetOption97", "1")
    pauseExecution(2000)

	log ("configureTasmota", "Waiting for dimmer/fan functions.. ", 0)
    callTasmota("BACKLOG0", "TuyaMCU 11,1; TuyaMCU 21,10; TuyaMCU 12,9")
	log ("configureTasmota", "Waiting for reboot", 0)
    pauseExecution(5000)
    waitUntilPing()
    //callTasmota("TuyaMCU", "11,1")
    //callTasmota("TuyaMCU", "21,10")
    //callTasmota("TuyaMCU", "12,9")
	log ("configureTasmota", "resending for dimmer/fan functions.. 10 sec", 0)
    callTasmota("BACKLOG0", "TuyaMCU 11,1; TuyaMCU 21,10; TuyaMCU 12,9")
	log ("configureTasmota", "Waiting for reboot", 0)
    pauseExecution(5000)
    waitUntilPing()

	log ("configureTasmota", "Waiting for options .. 10 sec", 0)
    callTasmota("BACKLOG", "setoption20 0; setoption54 1; webbutton1 Fan; webbutton2 Light; DimmerRange 100,1000; ledtable 0")
    pauseExecution(3000)
    waitUntilPing()
    off()
    fanOff()
    //if (device.currentValue("switch") != "on") off()
    //if (device.currentValue("speed") != "off") fanOff()
}

//Installed gets run when the device driver is selected and saved
def installed(){
	log ("Installed", "Installed with settings: ${settings}", 0)
}

//Updated gets run when the "Save Preferences" button is clicked
def updated(){
	log ("Update", "Settings: ${settings}", 0)
	initialize()
	log ("updated", "Setting devicename in Tasmota web-ui to ${device.displayName}", 0)
    callTasmota("DeviceName", device.displayName)
}

//Uninstalled gets run when called from a parent app???
def uninstalled() {
	log ("Uninstall", "Device uninstalled", 0)
}

// Handle command timeouts (not sure why we can't just do this in callTasmota?)
// This function is called settings.timeout milliseconds after the the transaction started.
// If the transaction has timed then it resets out and resets any temporary values.
def watchdog(){
    if (state.inTransaction == false ) {
        log ("watchdog", "All normal. Not in a transaction.", 2)
        }
    else
        {
        log ("watchdog", "Transaction timed out. Cancelled.", 2)
        updateStatus("Complete:Timeout") 
        //If the transaction has not finished successfully then we should mark it complete now the timeout has expired.   
        state.inTransaction = false
        }
    
    state.remove("ruleInjection")
    log ("watchdog", "Finished.", 1)
    
    //If the last command was a backlog then we don't really know what happened so we should do a refresh.
    if ( state.Action == "BACKLOG" ) {
        log ("watchdog", "Last command was a BACKLOG. Initiating STATE refresh for current settings.", 0)
        //Calculate when the current operations should be finished and schedule the "STATE" command to run after them.
        def parameters = ["STATE",""]
        //runInMillis(remainingTime() + 500, "callTasmota", [data:parameters])
        runInMillis(remainingTime() + 500, "refresh", [data:[]])

        state.LastSync = new Date().format('yyyy-MM-dd HH:mm:ss')
        }
    }

def lanSent(hubitat.device.HubResponse hubResponse) {
    // This is called back when callTasmota lanmessage is sent out
    // Can use this as verification as command sent, but at some point the device itself will do a callback
    // that will get dispatched to syncTasmota to update status of device inside hubitat
    def status = hubResponse.status
    def json = hubResponse.json

    log("Lan Sent", json, 2)
    //Get the command and value that was submitted to the callTasmota function
    Action = state.Action
    ActionValue = state.ActionValue    
    
    log ("lanSent", "Flags are Action:${state.Action}  ActionValue:${state.ActionValue}", 2)
    
    //Test to see if we got a warning from Tasmota
    if (status as Integer != 200) {
        log ("lanSent","A warning was received from Tasmota. Review the message '${json}' and make appropriate changes.", -1)
        updateStatus("Complete:Failed")
    }
    if (state.inTransaction) {
        log("lanSent", "Finished transaction", 1)
    }
    state.inTransaction = false
    return
}

// Send a command to the wall-swich
def callTasmota(action, receivedvalue){
	log ("callTasmota", "Sending command: ${action} ${receivedvalue}", 0)
    //Update the status to show that we are sending info to the device
    
    def actionValue = receivedvalue.toString()
    if (actionValue == "") {actionValue = "None"}
    state.Action = action
    state.ActionValue = actionValue
    
	//Capture what we are doing so we can validate whether it executed successfully or not
    //We are essentially using the Attribute "Action" as a container for global variables.
    state.startTime = now()
    log ("callTasmota","Opening Transaction", 2)
    state.inTransaction = true
    
    //Watchdog is used to ensure that the transaction state is closed after the expiration time. Subsequent data will be ignored unless it is a TSync request.
    log ("callTasmota", "Starting Watchdog", 3)
    runInMillis(settings.timeout, "watchdog")
    path = "/cm?user=${username}&password=${password}&cmnd=${action} ${actionValue}"
    
    def newPath = cleanURL(path)

    log ("callTasmota", "Path: ${newPath}", 3)
    try {
            def hubAction = new hubitat.device.HubAction(
                [
                    method: "GET",
                    path: newPath,
                    headers: [HOST: "${settings.destIP}:${settings.destPort}"]
                ], 
                "${settings.destIP}:${settings.destPort}", // dni
                [
                    callback: lanSent
                ]
            )
            log ("callTasmota", "hubaction: ${hubAction}", 3)
            sendHubCommand(hubAction)
            updateStatus("Sent:${action} ${receivedvalue}")
        }
        catch (Exception e) {
            log ("calltasmota", "Exception $e in $hubAction", -1)
        }
    //The response to this HubAction request will come back to the parse function.
    log ("callTasmota","Exiting", 1)
}

// Anytime RULE3 fires on the hub (see configure() for RULE3 definition), the
// device will make a http request back to the hub with the current status of the device
// which will be handled by this parse function.  It simply dispatches messages with
// the TSYNC var (so we know it's a request that is generated from RULE3) to the
// syncTasmota function.  RULE3 will fire in response to BOTH hub-initiated 
// commands and physical changes at the switch.
def parse(LanMessage){
    log ("parse", "Entering, data received.", 1)
    log ("parse","data is ${LanMessage}", 3)
    
    def msg = parseLanMessage(LanMessage)
    def body = msg.body
    log ("parse","body is ${body}", 2)
    state.lastMessage = state.thisMessage
    state.thisMessage = msg.body       
        
	//TSync message use single quotes and must be cleaned up to be handled as JSON later
	body = body?.replace("'","\"") 
	//Convert all the contents to upper case for consistency
	body = body?.toUpperCase()

	//Search body for the word TSYNC while it is still in string form
	if (body.contains("TSYNC")==true ) {
		log ("parse","Exit to syncTasmota()", 1)
		syncTasmota(body)
    } else { 
        log ("parse", "Not a TSYNC message from the device, so ignoring", 1)
    }
}

// Main callback handler when the Tasmota device does a HTTP request with a status update.  This function
// is responsible for keeping the device driver status in sync with the actual wall-switch, whether
// the switch was updated through this driver with a "callTasmota" web request, or whether the wall-switch
// buttons were physically pushed by a person.
def syncTasmota(body){
    log ("syncTasmota", "Data received: ${body}", 0)
    //This is a special case that only happens when the rules are being injected
    if (state.ruleInjection == true){
        log ("syncTasmota", "Rule3 special case complete.", 1)
        state.ruleInjection = false
        state.inTransaction = false
        log ("syncTasmota","Closing Transaction", 2)
        updateStatus("Complete:Success")
        return
    } 
    log ("syncTasmota", "Tasmota Sync request processing.", 1)
    state.Action = "Tasmota"
    state.ActionValue = "Sync"
    state.lastTasmotaSync = new Date().format('yyyy-MM-dd HH:mm:ss')
    
    //Now parse into JSON to extract data.
    body = parseJson(body)
    
    //Preset the values for when the %vars% are empty
    switch1 = -1 ; dimmer = -1 ; fade = "SAME" ; fadespeed = -1 ; speed = -1 ; fanSwitch = -1;
    
    //A value of '' for any of these means no update. Probably because the device has restarted and the %vars% have not repopulated. This is expected.
    if (body?.SWITCH1 != '') { switch1 = body?.SWITCH1 ; log ("syncTasmota","Switch is: ${switch1}", 2) }
    if (body?.FANSPEED != '') { fanSpeed = body?.FANSPEED.toInteger() ; log ("syncTasmota","fanSpeed is: ${fanSpeed}", 2) }
    if (body?.FANSWITCH != '') { fanSwitch = body?.FANSWITCH ; log ("syncTasmota","fanSwitch is: ${fanSwitch}, and fanSpeed is: ${fanSpeed}", 2) }
    if (body?.DIMMER != '') { dimmer = body?.DIMMER.toInteger() ; log ("syncTasmota","Dimmer is: ${dimmer}", 2) }
    
    //Now apply any changes that have been found. In Tasmota, "power" is the switch state unless referring to sensor data.
    //Only changes will get logged so we can report everything. 
    if ( switch1.toInteger() == 0 ) sendEvent(name: "switch", value: "off", descriptionText: "The switch was turned off.")
    if ( switch1.toInteger() == 1 ) sendEvent(name: "switch", value: "on", descriptionText: "The switch was turned on.")
    
    //Send fanSpeed event if we have new data. Ignore anything less than 0.
    // If fanSwitch is 0 then need to make sure fanSpeed is set to zero (turned off)
    if ( fanSwitch.toInteger() == 0 && fanSpeed > 0) {
        log ("syncTasmota", "Fan turned off", 2)
        fanSpeed =-1
        sendEvent(name: "speed", value: "off", descriptionText: "speed was set to off.")
    } 
    if ( fanSpeed >= 0 ) {
        speed = fanSpeeds.find{ it.value==fanSpeed-1 }?.key
        log("syncTasmota", "Fan speed set to ${fanSpeed} = ${speed}", 2)
        sendEvent(name: "speed", value: speed, descriptionText: "speed was set to ${speed}.")
    }
    
    //Send dimmer events if we have new data. Ignore anything less than 0.
    if ( dimmer >= 0 ) sendEvent(name: "level", value: dimmer, unit: "Percent" )
    
    updateStatus ("Complete:Tasmota Sync")
    log ("syncTasmota", "Sync completed. Exiting", 1)
    return
}


// Helper function to send event message and log them.
def updateStatus(status){
    log ("updateStatus", status, 1)
    sendEvent(name: "Status", value: status )
}

private log(name, message, int loglevel){

    def deviceName = bold(blue(device.displayName))
    def msg = "${deviceName}:${dodgerBlue(name)} ${goldenrod(message)}"

    //This is a quick way to filter out messages based on loglevel
	int threshold = settings.logging_level
    if (loglevel > threshold) {return}

    if (loglevel <= 0) {
        log.info(msg)
    } else {
        log.debug(msg)
    }

}

//Functions to enhance text appearance
String bold(s) { return "<b>$s</b>" }
String italic(s) { return "<i>$s</i>" }
String underline(s) { return "<u>$s</u>" }

//String tomato(s) { return '"<p style="background-color:Tomato;">' + s + '</p>' }
//String test(s) { return '<body text = "#00FFFF" bgcolor = "#808000">' + s + '</body>'}

String green(s) { return '<font color = "green">' + s + '</font>'}
//Blues
String dodgerBlue(s) { return '<font color = "DodgerBlue">' + s + '</font>'}
String blue(s) { return '<font color = "blue">' + s + '</font>'}
//Browns
String goldenrod(s) { return '<font color = "Goldenrod">' + s + '</font>'}
//Grays
String black(s) { return '<font color = "Black">' + s + '</font>'}


//This does not work fully yet but I'm leaving it here as I hope to get this working at some point and the basic code does work to show a tooltip.
def tooltip (String message) {
s = '<style> .tooltip { position: relative; display: inline-block; border-bottom: 1px dotted black; }'
s = s + '.tooltip .tooltiptext { visibility: hidden; width: 120px; background-color:lightsalmon; background-color: black; color: #fff; text-align: center; padding: 5px 0; border-radius: 6px; position: absolute; z-index: 1; } '
s = s + '.tooltip:hover .tooltiptext { visibility: visible; background-color:lightsalmon; } </style>'
s = s + '<div class="tooltip">Help..<span class="tooltiptext">YYYYY</span> </div>'
s = s.replace("YYYYY", message) 
return s

}


//****** Supporting functions

private String convertIPtoHex(ipAddress) { 
    String hex = ipAddress.tokenize( '.' ).collect {  String.format( '%02X', it.toInteger() ) }.join()
    return hex
}

//Updates the device network information - Allows user to force an update of the device network information if required.
private updateDeviceNetworkID() {
	
    try{
    	log("updateDeviceNetworkID", "Settings are:" + settings.destIP, 3)
        def hosthex = convertIPtoHex(settings.destIP)
    	def desireddni = "$hosthex"
        
        def actualdni = device.deviceNetworkId
        
        //If they don't match then we need to update the DNI
        if (desireddni !=  actualdni){
        	device.deviceNetworkId = "$hosthex" 
            log("Action", "Save updated DNI: ${"$hosthex"}", 0)
         	}
        else
        	{
            log("Action", "DNI: ${"$hosthex"} is correct. Not updated. ", 2)
            }
        }
    catch (e){
    	log("Save", "Error updating Device Network ID: ${e}", -1)
     	}
}

// Waits until at least 5 pings are successful
def waitUntilPing() {
    def success = 0
    while (success < 5) {
        hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(settings.destIP, 5)
        success = pingData["packetsReceived"]
        log("waitUntilPing", "Got successfull ping count of ${success}", 0)
        pauseExecution(1000)
    }
}

//Cleans up Tasmota command URL by substituting for illegal characters
//Note: There is no way to pass a double quotation mark " 
def cleanURL(path){
    log ("cleanURL", "Fixing path: ${path}", 3)
    //We obviously have to do this one first as it is the % sign. Characters with a leading \ are escaped.
    path = path?.replace("%","%25") 
    //And then we can do the rest which also use this symbol
    path = path?.replace("\\","%5C") 
    path = path?.replace(" ","%20") 
    path = path?.replace('"',"%22") 
    path = path?.replace("#","%23") 
    path = path?.replace("\$","%24") 
    path = path?.replace("+","%2B") 
    path = path?.replace(":","%3A") 
    path = path?.replace(";","%3B") 
    path = path?.replace("<","%3C") 
    path = path?.replace(">","%3E") 
    path = path?.replace("{","%7B") 
    path = path?.replace("}","%7D") 
    log ("cleanURL", "Returning fixed path: ${path}", 3)
    return path
    } 

//Returns the maximum amount of time until a Transaction is guaranteed to be finished.  Used to slow sequential BACKLOG transactions.
def remainingTime(){
    if (state.inTransaction == true ) {
        start = state.startTime
        remainingTime = ( start + settings.timeout - now() )
    }
    else { remainingTime = 0 }
    //remainingTime = 3000
    log ("remainingTime", "Remaining time ${remainingTime}", 3)
    return remainingTime  
}


