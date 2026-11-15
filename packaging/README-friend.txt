PENTECOST
=========

Pentecost is a Minecraft 1.20.1 mod loader that runs Forge mods. You need a Windows PC, a Minecraft Java Edition account, and about 2 GB of free disk space.

This zip holds Pentecost only. Minecraft and Forge are not inside it. Pentecost downloads them from Mojang and Forge the first time you start it.


INSTALL (Minecraft Launcher)
----------------------------
1. Unzip the whole zip to a folder. Do not run the installer from inside the zip.
2. Close the Minecraft Launcher completely, also in the system tray.
3. Double click "Install Pentecost.cmd".
4. Open the Minecraft Launcher. Pick the "Pentecost" profile next to the Play button.
5. Press Play. The first start downloads several hundred MB and takes a few minutes.

Put your mods in: %APPDATA%\.minecraft\pentecost\run\mods


SIGN IN
-------
Pentecost uses the account you are signed in with when you start it from a launcher. If you start it another way, it asks you to sign in with Microsoft. Pentecost never stores your password.


USE WITH THE MODRINTH APP (optional)
------------------------------------
1. Run "Install Pentecost.cmd" once, so the files are in place.
2. In the Modrinth App, open the instance you want to use, then Settings, then Launch hooks.
3. In the Wrapper field, enter the full path of this file:
   %APPDATA%\.minecraft\pentecost\pentecost-wrapper.cmd
4. Start the instance from the Modrinth App. Modrinth signs you in and Pentecost starts with that account.

You need Java 21 for this. If the wrapper cannot find it, install Temurin 21, or set the PENTECOST_JAVA environment variable to the path of its java.exe. The wrapper writes a log to %APPDATA%\.minecraft\pentecost\run\logs\wrapper.log. The log never holds your sign-in token.


MEMORY
------
The installer picks a memory amount from your PC's RAM. To change it, edit the JVM arguments of the Pentecost profile in the Minecraft Launcher. For the Modrinth wrapper, create a file named jvm-args.txt in %APPDATA%\.minecraft\pentecost with one flag per line, for example:
-Xms6G
-Xmx6G


UNINSTALL
---------
Close the Minecraft Launcher, then double click "Uninstall Pentecost.cmd". Your worlds, mods and settings stay in %APPDATA%\.minecraft\pentecost\run unless you choose to delete them when the uninstaller asks.


LICENCES
--------
See LICENSE.txt and THIRD-PARTY-NOTICES.txt in this folder.
