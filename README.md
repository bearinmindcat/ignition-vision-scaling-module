# Ignition Vision Scaling Fix

[![Build](https://github.com/bearinmindcat/ignition-vision-scaling-module/actions/workflows/build.yml/badge.svg)](https://github.com/bearinmindcat/ignition-vision-scaling-module/actions/workflows/build.yml)
[![Ignition](https://img.shields.io/badge/Ignition-8.1.35+-rebeccapurple.svg)](https://inductiveautomation.com/)
[![Vision](https://img.shields.io/badge/Vision-clients-orange.svg)](#requirements)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Keeps numbers and labels readable when a Vision client window is smaller than its screens were designed for: half the screen (side by side or top and bottom), a quarter, a phone over Remote Desktop, or a PC at 125 % display scaling.

Without it, value boxes cut their numbers off: only a thin slice through the middle of the digits shows, so they read as dots and dashes, and labels end in "..." even when there is room. With it, the same boxes show the whole number, a little smaller. At full size nothing changes.

Forum thread with the same problem: [Layout on numeric text field versus numeric label](https://forum.inductiveautomation.com/t/layout-on-numeric-text-field-versus-numeric-label/80923). The workarounds there (taller fields, or turning Scale Font off and adding a resize script to every field) mean editing every component; this module does it for every window, with nothing to change in the project.

## Why Vision cuts things off

When the window is smaller than the design size, Vision's relative layout shrinks each box and the text inside it, but not the box's border. Ignition's look and feel gives a text field a fixed 8 px of border and padding in height (4 at the top, 4 at the bottom) at every size.

- **Full size:** a value box designed 34 px tall loses 8 px to the border. That doesn't matter: there's still room for the digits.
- **Half screen:** the same box comes out about 12 px tall. The border still takes 8 px, leaving 4 px for text that needs about 11. Only the middle slice of the digits shows.

Smaller causes:

- **"..." on labels and buttons:** Vision rounds the shrunk font to a whole point size (the smaller of the width and height ratios, rounded), sometimes up, so the text ends up a bit wider than its box.
- **Docked windows keep their size:** a title bar or menu docked at the edge stays its full pixel size, so the main screen is squeezed into whatever space is left.
- **Windows display scaling:** at 125 %, Java reports a 1920x1200 screen as 1536x960. To Vision the screen is 20 % smaller, so a window designed 1920 wide is shrunk to 80 % even when maximized, and more again at half screen.

## What it does

After Vision lays a window out, each text field, label and button drawn below 97 % of its design height gets:

- **Text fields:** a 1 px line border whose padding shrinks with the box, in place of the fixed 8 px.
- **Fields, labels and buttons with plain text:** the exact (fractional) font size, shrunk in quarter-point steps until the text fits the height and width it has. HTML text is left alone (it wraps instead of ending in "...").

It also lowers the client's minimum size. Vision doesn't lay its screens out smaller than the project's Minimum Size (Project Properties → Vision → User Interface, 800x600 by default): below that it adds scroll bars and part of the screen is out of view. The module lowers that limit to 100x100 in each running client, so the whole screen stays in view and keeps shrinking. The project's setting isn't changed.

Components back at (nearly) their design size get Vision's own border and font back. Nothing is saved to the project, and uninstalling the module puts everything back in open clients right away.

Vision re-applies its own rounded font on every layout pass whenever the rounded size differs from the component's current one. So the module also writes its size into the component's layout font ratios: Vision's next pass arrives at the same size and leaves the font alone, instead of the two undoing each other. If anything else keeps changing a component back (more than 5 times a second), the module leaves that component alone for 5 seconds.

## What it doesn't fix

- **Docked windows** (a title bar or menu docked at the edge) keep their pixel size, so in a small window they take most of the space.
- **Anchored components** keep their pixel size too: a header bar or tab strip anchored to an edge stays its full height. Below about 800x600 (where Vision used to add scroll bars) they can overlap the shrinking parts of the screen.
- **Proportions:** Vision keeps a screen's proportions, so in a tall, narrow window the screen comes out small with blank space above and below.
- **Tables** (alarm status table rows and headers) and images keep their size.
- **Trade-off:** labels that Vision ended with "..." now show their whole text, but smaller.

For docked windows and anchored components, Java can zoom the whole client evenly, docked windows included: `-Dsun.java2d.uiScale=0.5` makes Java report the screen twice as large, so Vision lays screens out at full size and Java draws them at half size. It works together with this module. In our tests (Vision Client Launcher 1.1.35) the launcher's JVM arguments reached only the launcher itself, not the client it starts; setting `JAVA_TOOL_OPTIONS=-Dsun.java2d.uiScale=0.5` before starting the launcher did reach the client.

## Requirements

- Ignition 8.1.35 or newer 8.1 (8.3 isn't supported yet)
- Vision clients (the Designer keeps Vision's own behavior while windows are edited; not Perspective)

## Installation

1. On the Gateway, go to **Config → Modules → Install or Upgrade a Module** and upload the `.modl`.
2. Restart the Vision clients (it was tested with newly started clients).

An unsigned build needs `wrapper.java.additional.N=-Dignition.allowunsignedmodules=true` (the next free `N`) in `ignition.conf` and a Gateway restart.

## Settings on one PC

Java system properties for the client, for example set through `JAVA_TOOL_OPTIONS` before starting the Vision Client Launcher:

- `-Dvisionscaling.disabled=true` turns the module off in that client. Useful for comparing a client with and without it side by side.
- `-Dvisionscaling.minSize=640x480` sets the client's minimum size (100x100 by default); `-Dvisionscaling.minSize=off` keeps the project's Minimum Size and its scroll bars.

## Building

```
./gradlew build             # build/Vision-Scaling-Fix.unsigned.modl
```

Needs JDK 17 or newer (JDK 25 is too new for Gradle 8.10). After changing the version in `gradle.properties`, run `./gradlew clean build`.

To sign the module, set `ignition.signing.keystoreFile`, `keystorePassword`, `certFile`, `certAlias` and `certPassword` in `~/.gradle/gradle.properties`. The build then also writes `build/Vision-Scaling-Fix.modl`.

## License

[MIT](LICENSE)
