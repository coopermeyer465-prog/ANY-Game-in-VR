# VR Input Bridge (ANY Game in VR)

A prototype system that allows playing normal PC games in VR using a Meta Quest headset.

## Overview

The VR Input Bridge is an experimental project that converts standard PC games into a VR-like experience by:

- Mapping headset movement to in-game camera/mouse movement
- Injecting input directly into games when the cursor is hidden
- Streaming the computer display to a Meta Quest headset
- Creating a head-locked virtual screen for gameplay

The goal is to make non-VR PC games playable in VR without requiring game mods.

---

## Current Status

This is an early prototype, but core systems are already working.

### Working Features

- Low-latency head tracking mapped to in-game camera movement
- Quest to computer connection over local IP
- Input injection system (active when mouse is hidden in-game)
- Window capture and dynamic switching based on active window
- PC to Quest streaming pipeline (sending side functional)

### Work In Progress

- Quest receiver (currently displays a static frame instead of live video)
- Streaming performance and frame synchronization (currently low FPS on receiver)
- Controller to keyboard/mouse input mapping
- UI system (settings, sensitivity, remapping)
- Stability and error handling

---

## Demo

(Add your YouTube video link here)

---

## How It Works (High Level)

1. The Meta Quest headset sends head movement data over a local IP connection  
2. The computer receives this data and converts it into camera/mouse input  
3. Input is injected into games when the system detects a hidden cursor  
4. The computer captures and streams the active window  
5. The Quest displays the stream as a head-locked virtual screen  

---

## Vision

The long-term goal is to create a system that allows:

- Playing most PC games in VR without native support  
- Controller-to-keyboard/mouse mapping  
- Gesture-based inputs (e.g., swinging controllers for actions)  
- Automatic window scaling and VR display optimization  

---

## Platform Goals

- macOS (current prototype)
- Windows (planned)

---

## Downloads

Mac Receiver App:  
https://github.com/coopermeyer465-prog/ANY-Game-in-VR/blob/main/downloads/Mac-Receiver.zip

Quest App Installation:

The Quest app is installed through the Mac receiver application.

- Connect the Quest headset via USB  
- Launch the Mac receiver app  
- Use the "Install Quest App" button  

---

## Collaboration

This project is currently a prototype, and I am looking for developers interested in helping turn it into a full product.

Areas that would be especially useful:

- VR development (Unity, OpenXR, Quest SDK)
- Streaming and encoding optimization
- Input systems (keyboard/mouse/game translation)
- UI/UX development

If you're interested, feel free to open an issue or reach out.

---

## Notes

This is an experimental prototype and is not yet completely optimized or stable.

---

## Project Goal

Create a universal bridge between flat-screen games and VR.
