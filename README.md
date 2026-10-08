# GeoSurvey Cartographer
Takes chunks you load in a server, and posts them to a GeoSurvey node. Such as https://avience.live/ . An alternative to other web based world/server live views, Decentralized by nature, and built to withstand single points of failure. 
Has many ways to protect a live view from being overwritten by another server. 

Runs beside another map mod, such as Xaero's or JourneyMap.

Minecraft 26.2, Fabric, Java 25. Client side.

For players
Put the jar in your mods folder, with Fabric API. GeoSurvey 0.1.12-i and newer already hold it; do not add it beside them.

Open the settings (Mod Menu, or the Open settings key) and type the collector's address. Contributing starts.


Commands:

/geosurvey share	shows whether you are contributing

/geosurvey share on, off	turns contributing on or off

/geosurvey collector <address> sets the collector

/geosurvey collector find	lists collector addresses to choose from

/geosurvey collector clear	forgets the collector

/geosurvey server	lists the approved servers

/geosurvey server add <address>, remove <address>	edits the approved servers

/geosurvey friend add <player>, remove <player>	edits the friends list

/geosurvey claim ...	creates, edits, lists, shares and removes a claim

/geosurvey marker ...	places, lists, edits, shares and removes a marker

Build
JDK 25 and Python 3, a built Sandpaper checkout beside this one (../sandpaper), and the Minecraft and Fabric jars in libs/. Then:

python build.py

License
AGPL-3.0-or-later. See LICENSE.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
