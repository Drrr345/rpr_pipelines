def call(String osName, String tool_version, Map options, boolean clear=true, String matlib='')
{
    // temp code for deleting old plugin
    if (osName == 'Windows'){
        println '[INFO] Uninstalling old plugin'
        uninstallMSI("Radeon%Blender%", options.stageName)
    }
   
    if (checkExistenceOfBlenderAddon(osName, tool_version, options)) {
        println '[INFO] Current plugin is already installed.'
        return false

    } else {
        println '[INFO] Uninstalling Blender addon'
        uninstallBlenderAddon(osName, tool_version, options)
        println '[INFO] Installing plugin'
        installBlenderAddon(osName, tool_version, options)
        if (matlib){
            echo '[INFO] Reinstalling Material Library'
            uninstallMSI("Radeon%Material%", options.stageName)
            installMSI(matlib, options.stageName)
        }
        return true
    }
}


def checkExistenceOfBlenderAddon(String osName, String tool_version, Map options) 
{

    println "[INFO] Checking existence of the Blender Addon on test PC."
    println "[INFO] Installer name: ${options.commitSHA}_${osName}.zip"
    println "[INFO] Installed Blender Addon commit hash: ${options.commitShortSHA}"

    try {

        String blenderAddonCommitHash = ""

        switch(osName)
        {
            case 'Windows':
                // Reading commit hash from installed addon
                bat """
                    echo import getpass >> getInstallerCommitHash.py
                    echo commit_hash = "unknown" >> getInstallerCommitHash.py
                    echo init_path = r"C:\\Users\\{username}\\AppData\\Roaming\\Blender Foundation\\Blender\\${tool_version}\\scripts\\addons\\rprblender\\__init__.py".format(username=getpass.getuser()) >> getInstallerCommitHash.py
                    echo with open(init_path) as f: >> getInstallerCommitHash.py
                    echo     lines = f.readlines() >> getInstallerCommitHash.py
                    echo     for line in lines: >> getInstallerCommitHash.py
                    echo         if line.startswith("version_build"): >> getInstallerCommitHash.py 
                    echo             commit_hash = line[17:24] >> getInstallerCommitHash.py
                    echo print(commit_hash) >> getInstallerCommitHash.py 
                """

                blenderAddonCommitHash = python3("getInstallerCommitHash.py").split('\r\n')[2].trim()
                break;

            case 'OSX':
                // Reading commit hash from installed addon
                sh """
                    echo import getpass >> getInstallerCommitHash.py
                    echo commit_hash = '"unknown"' >> getInstallerCommitHash.py
                    echo init_path = r'"/Users/{username}/Library/Application Support/Blender/${tool_version}/scripts/addons/rprblender/__init__.py".format(username=getpass.getuser())' >> getInstallerCommitHash.py
                    echo with open'(init_path)' as f: >> getInstallerCommitHash.py
                    echo '    'lines = f.readlines'()' >> getInstallerCommitHash.py
                    echo '    'for line in lines: >> getInstallerCommitHash.py
                    echo '        'if line.startswith'("version_build")': >> getInstallerCommitHash.py 
                    echo '            'commit_hash = line[17:24] >> getInstallerCommitHash.py
                    echo print'(commit_hash)' >> getInstallerCommitHash.py 
                """ 

                blenderAddonCommitHash = python3("getInstallerCommitHash.py").trim()
                break;

            default:
                // Reading commit hash from installed addon
                sh """
                    echo import getpass >> getInstallerCommitHash.py
                    echo commit_hash = '"unknown"' >> getInstallerCommitHash.py
                    echo init_path = r'"/home/{username}/.config/blender/${tool_version}/scripts/addons/rprblender/__init__.py".format(username=getpass.getuser())' >> getInstallerCommitHash.py
                    echo with open'(init_path)' as f: >> getInstallerCommitHash.py
                    echo '    'lines = f.readlines'()' >> getInstallerCommitHash.py
                    echo '    'for line in lines: >> getInstallerCommitHash.py
                    echo '        'if line.startswith'("version_build")': >> getInstallerCommitHash.py 
                    echo '            'commit_hash = line[17:24] >> getInstallerCommitHash.py
                    echo print'(commit_hash)' >> getInstallerCommitHash.py 
                """         
                blenderAddonCommitHash = python3("getInstallerCommitHash.py").trim()
        }

        println "[INFO] Built Blender Addon commit hash: ${blenderAddonCommitHash}"

        return options.commitShortSHA == blenderAddonCommitHash

    } catch (e) {
        echo "[ERROR] Failed to compare installed and built plugin. Reinstalling..."
        println(e.toString())
        println(e.getMessage())
    }
    
    return false
}


def uninstallBlenderAddon(String osName, String tool_version, Map options)
{
    // Remove RadeonProRender Addon from Blender  
    try {
        switch(osName)
        {
            case 'Windows':
                bat """
                    echo "Disabling RPR Addon for Blender." >> ${options.stageName}.uninstall.log 2>&1

                    echo import bpy >> disableRPRAddon.py
                    echo bpy.ops.preferences.addon_disable(module="rprblender")  >> disableRPRAddon.py
                    echo bpy.ops.wm.save_userpref() >> disableRPRAddon.py
                    "C:\\Program Files\\Blender Foundation\\Blender ${tool_version}\\blender.exe" -b -P disableRPRAddon.py >> ${options.stageName}.uninstall.log 2>&1

                    echo "Removing RPR Addon for Blender." >> ${options.stageName}.uninstall.log 2>&1

                    echo import bpy >> removeRPRAddon.py
                    echo bpy.ops.preferences.addon_remove(module="rprblender") >> removeRPRAddon.py
                    echo bpy.ops.wm.save_userpref() >> removeRPRAddon.py

                    "C:\\Program Files\\Blender Foundation\\Blender ${tool_version}\\blender.exe" -b -P removeRPRAddon.py >> ${options.stageName}.uninstall.log 2>&1
                """
                break;
            // OSX & Ubuntu18
            default:
                sh """
                    echo "Disabling RPR Addon for Blender." >> ${options.stageName}.uninstall.log 2>&1

                    echo import bpy >> disableRPRAddon.py
                    echo bpy.ops.preferences.addon_disable'(module="rprblender")'  >> disableRPRAddon.py
                    echo bpy.ops.wm.save_userpref'()' >> disableRPRAddon.py
                    blender -b -P disableRPRAddon.py >> ${options.stageName}.uninstall.log 2>&1

                    echo "Removing RPR Addon for Blender." >> ${options.stageName}.uninstall.log 2>&1

                    echo import bpy >> removeRPRAddon.py
                    echo bpy.ops.preferences.addon_remove'(module="rprblender")' >> removeRPRAddon.py
                    echo bpy.ops.wm.save_userpref'()' >> removeRPRAddon.py

                    blender -b -P removeRPRAddon.py >> ${options.stageName}.uninstall.log 2>&1
                """
        }
    }
    catch(e)
    {
        echo "[ERROR] Failed to delete RPR Addon from Blender"
        println(e.toString())
        println(e.getMessage())
    }
}
    


def installBlenderAddon(String osName, String tool_version, Map options)
{
    // Installing RPR Addon in Blender
    try
    {
        switch(osName)
        {
            case 'Windows':
                bat """
                    echo "Installing RPR Addon in Blender" >> ${options.stageName}.install.log
                    echo import bpy >> registerRPRinBlender.py
                    echo addon_path = "${CIS_TOOLS}\\..\\PluginsBinaries\\\\${options.commitSHA}_${osName}.zip" >> registerRPRinBlender.py
                    echo bpy.ops.preferences.addon_install(filepath=addon_path) >> registerRPRinBlender.py
                    echo bpy.ops.preferences.addon_enable(module="rprblender") >> registerRPRinBlender.py
                    echo bpy.ops.wm.save_userpref() >> registerRPRinBlender.py

                    "C:\\Program Files\\Blender Foundation\\Blender ${tool_version}\\blender.exe" -b -P registerRPRinBlender.py >> ${options.stageName}.install.log 2>&1
                """
                break;
            // OSX & Ubuntu18
            default:
                sh """
                    echo "Installing RPR Addon in Blender" >> ${options.stageName}.install.log
                    echo import bpy >> registerRPRinBlender.py
                    echo addon_path = '"${CIS_TOOLS}/../PluginsBinaries/${options.commitSHA}_${osName}.zip"' >> registerRPRinBlender.py
                    echo bpy.ops.preferences.addon_install'(filepath=addon_path)' >> registerRPRinBlender.py
                    echo bpy.ops.preferences.addon_enable'(module="rprblender")' >> registerRPRinBlender.py
                    echo bpy.ops.wm.save_userpref'()' >> registerRPRinBlender.py

                    blender -b -P registerRPRinBlender.py >> ${options.stageName}.install.log 2>&1
                """
        }
    }
    catch(e)
    {
        println "[ERROR] Failed to install RPR Addon in Blender"
        println(e.toString())
        println(e.getMessage())
    }
}


def installMatLibLinux(String msiName, String logs)
{
    receiveFiles("bin_storage/RadeonProRenderMaterialLibraryInstaller_2.0.run", "${CIS_TOOLS}/../TestResources/")

    sh """
        #!/bin/bash
        ${CIS_TOOLS}/../TestResources/RadeonProRenderMaterialLibraryInstaller_2.0.run --nox11 -- --just-do-it >> ${logs}.matlib.install.log 2>&1
    """
}
