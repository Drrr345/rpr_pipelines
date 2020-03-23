def call(String name, String logs="") //for example: "Blender%' and Version ='2.80.0"
{
    logs = logs ?: "${STAGE_NAME}"
    try{
        powershell"""
            \$rpr_plugin = Get-WmiObject -Class Win32_Product -Filter "Name LIKE '${name}'"
            if (\$rpr_plugin) {
                Write "Uninstalling..."
                \$rpr_plugin_id = \$rpr_plugin.IdentifyingNumber
                start-process "msiexec.exe" -arg "/X \$rpr_plugin_id /qn /quiet /L+ie ${logs}.msi.uninstall.log /norestart" -Wait
            }else{
                Write "Plugin not found"
            }
        """
    }
    catch(e)
    {
        println("[ERROR] Failed to uninstall ${name} plugin.")
        println(e.toString())
        println(e.getMessage())
    }
}
