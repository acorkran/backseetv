# BackSeeTV — settings and FAQ

What each setting does, and answers to the questions that come up. For installing, see
[SETUP.md](SETUP.md).

## The phone remote

**What's on the main screen?**

- **Top bar:** the app icon (tap it for the menu), **All screens**, **Leader-only audio**, and three
  connection icons.
- **Screens list:** one row per screen, with what it's playing. Tap a screen's row to include or leave
  out that screen. This phone's own row (when it's a screen too) is split: the left half of its
  preview ticks it, the right half makes it full screen.
- **Under the list:** **This phone as playback** and **Sync Playback**.
- **Below:** now playing, play controls, and Light, Sleep, Mute, Vol +/−, Toddler, Power and
  Check-in.

**What do the three icons in the top corner mean?**
Bluetooth, hotspot and shared Wi-Fi. Each is lit while it carries a link to a screen. Tap them for a
one-line summary of each.

**What's in the menu?**
**Settings** (the selected screen's settings), **Connection…**, **Pair a screen**, any nearby
screens this phone isn't paired with, and **Shut down BackSeeTV** at the bottom.

**What's the difference between closing the app and Shut down?**
Leaving the app only minimizes it: a remote sits idle while the TV plays, and that's fine.
**Shut down BackSeeTV** stops everything that holds a radio: its Bluetooth links, its hotspot, a
synced group, and this phone's own player if it's a screen. Opening the app starts it all again.

**What's All screens?**
Every command goes to every screen this phone can reach. Untick it and tap rows to pick some.

**What's Sync Playback?**
The selected screens play the same thing in step. One is the leader, marked with a gold crowned "L";
the others follow it. Syncing needs Wi-Fi between the screens. Away from home the phone starts its
own hotspot and a device-owner TV joins it by itself. If the screens can't link within about 45
seconds, the group switches itself off and says why.

**What's Leader-only audio?**
In a synced group, only the leader plays sound; the other screens stay silent so there's no echo.
Unticked, every screen plays its own sound at its own volume. It only shows while this phone is a
screen too, and is greyed out until Sync Playback is on.

**What's This phone as playback?**
The phone becomes a screen as well: the show plays in its row, it can join a synced group, and the
right half of its preview makes it full screen. The watch is the best way to drive it full screen.

**Why does Pair a screen ask me to turn Bluetooth on?**
The phone reaches the TV over Bluetooth, or over Wi-Fi when they share a network. If Bluetooth is off
it asks first (Android only lets an app ask, not switch it on). If you say no and Wi-Fi is off too,
it explains that one of them is needed.

## Connection… (on the phone)

**Which connection level should I use?**
**Automatic** (the default): Bluetooth for control, and Wi-Fi raised only for what needs it (the live
camera, imports, syncing). The others force one link: **Bluetooth only** (control only, works
anywhere), **TV hotspot**, **Remote hotspot**, or **Shared Wi-Fi** (everything on the same network,
like at home).

**What are the two "This phone's radios" switches?**
Android can leave a radio running after you switch it off: an open Bluetooth link survives in a
low-energy mode when "Bluetooth scanning" is on, and the app's own hotspot isn't the Mobile Hotspot in
Settings and outlives Wi-Fi being turned off. Ticked (the default), switching Bluetooth off cuts the
remote's Bluetooth links, and switching Wi-Fi off stops its hotspot. Unticked, a link that's already
open carries on. Either way, nothing new can connect while Bluetooth is off: Android doesn't allow it.

**How do I forget a TV?**
**Connection…** lists the paired screens with **Forget** beside each.

## The TV

**How do I get out of the show?**
The grown-up unlock: tap with four fingers, four times, within 3 seconds, then copy the code shown.
You land on the TV's remote page. Its menu has **Settings** and, at the bottom, **Exit to the
tablet's home screen** (the TV's way out, like the phone's Shut down). Left alone for a minute, the
remote page goes back to the locked show.

**How do I lock it in toddler mode from the TV itself?**
The **Toddler** button on the TV's remote page. It lights up and reads "Toddler: on" while it's on;
tap the preview (or its centred expand button) and the show comes back with the toddler lock on. Any
paired phone or watch can turn it on or off too.

**Why didn't the TV come back on when I started the car?**
**Power** on a remote puts the TV in standby: screen off, show paused, no sound. Plugging the charger
in or out doesn't wake it, so starting or stopping the car leaves it asleep. **Power** or **Play** on a
remote, or the tablet's own power button, wakes it with the sound as it was.

### TV settings

Grown-up unlock → menu → Settings, or from a paired phone: **Settings** in its menu (the title says
which screen you're changing).

**Unlock gesture**
- **Finger contact points**, **Taps needed**, **Within (seconds)**: the four-finger, four-tap,
  three-second gesture. Change it if a child works it out.
- **Type** and **Swipe direction**: a swipe instead of taps.

**Lock**
- **Toddler lock** (or **Kiosk lock** on a device-owner TV): pins the show so Home, Back and Recents
  do nothing. On a kiosk it's also the home screen and survives a restart.
- **Unlock method**: **Randomized PIN** (the default: copy a random code shown on screen, within the
  time and attempts below), **Preset PIN** (your own **Admin PIN**, default `1234`) or **Pattern**
  (experimental).
- **PIN length**, **Time to enter**, **Attempts allowed**: make the randomized code harder for a child
  who's learnt to read numbers. A toddler's accuracy won't match an adult's.

**Playback**
- **Light duration**: how long **Light** brightens the screen.
- **When the queue ends**: **loop forever**, or **stop** (the TV turns off after the last item).
- **Lock the volume buttons**: the TV's own volume buttons do nothing, so a child holding it can't
  blast it or silence it. Remotes still change the volume. **On by default on a TV**, off on a phone's
  own player. Toddler mode always locks them. It's per screen: in a group, one tablet can be locked and
  another not.

**Remotes**
- **Pair a remote**: shows the pairing code. Whoever can see the screen can pair, so the code is the
  key.
- **Unpair all remotes**: every paired phone and watch stops working until it scans the code again.
  Use it if a phone is lost or sold.

**Connection level**
- The TV's side of the link, plus **Hotspot**, **Show hotspot QR** and **Join a Wi-Fi network** (scan
  a Wi-Fi QR).

**Camera (still images)**
- **Periodic still snapshots**: the front camera updates a still image for the phone, the watch and
  the car dash (**Check-in**). Nothing is recorded or kept.

**Toddler play mode**
- **Start play mode**: the tap-the-letters screen over the show.
- **Sound**: **Muted**, **Letter sounds** or **Video sound**.

**Kiosk mode (device owner)**
- **Setup command**: the adb line for setting up device owner.
- **Remove device owner**: undoes the kiosk without a factory reset.

**Media**
- **Media folder**: where the shows are. By default, a folder called `BackSeeTV-Media` on internal
  storage or any SD card.
- **Rescan library**: after copying shows on by hand.

**Set-up check** names anything wrong (a missing permission, Bluetooth off) and offers the fix.
**About BackSeeTV & feature levels** explains what each setup route gives you.

## Shows

**What files play?**
`.mp4`, `.mkv` and `.m4v`, one folder per show inside `BackSeeTV-Media`, in name order. A new TV
plays **Shuffle All**: every show.

**My shows are in another folder.**
Run the Windows setup script: it finds videos anywhere on the tablet and its SD cards and offers to
move each folder into `BackSeeTV-Media` (a move on the same card: instant, no extra space). Or point
**Media folder** at it.

**Why didn't my music or photos show up?**
BackSeeTV only plays video. The setup script skips folders with no videos in them.

## Watch

**How do I update the watch?**
Over adb, like the first install (see SETUP B4). Update the phone first: an older phone offers its
older watch build, and the watch then asks to install from an unknown source.

**Which screen does the watch show?**
The one the phone remote is aimed at, or a synced group's leader. Its buttons go through the phone, so
they reach every screen the phone would. Tap the arc above play/pause to mute; the bezel changes the
volume.

## Privacy and security

**Can someone else control my TV?**
Only devices that scanned its pairing code. Another family's BackSeeTV nearby shows as "not paired"
and is never connected to. Controls, settings, camera stills and the video one TV streams to another
are encrypted or signed between paired devices.

**Does it need the internet?**
No. No accounts, no cloud, no ads.
