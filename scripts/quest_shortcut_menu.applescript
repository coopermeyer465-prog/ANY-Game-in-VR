set choiceList to {"Connect to Quest", "Disconnect Quest", "Change Head Movement Sensitivity", "Detect Quest IP", "Install Quest App"}
set picked to choose from list choiceList with prompt "Quest Headpose" default items {"Connect to Quest"}
if picked is false then
    return ""
end if
return item 1 of picked
