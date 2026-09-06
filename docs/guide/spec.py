"""
What to mark on each screenshot, in the order a player meets it.

Two decks, because hosting and joining are different jobs done by different
people. The split is not where it first looked, though: **the invite belongs to
the guest deck**, not the host's. Someone watching "how do I join a world" needs
to see where the link came from, and someone setting a world up does not need to
watch an invite go out before they can finish. So the guest deck opens on the
host's screen for three slides, then hands over.

There is no separate "states" deck either. A locked row and a live row are things
that happen *during* joining, and shown apart from it they are trivia.

`at` targets, in order of preference:

  "button:N"    Minecraft's grey buttons, found automatically and counted
                top-to-bottom - survives a screenshot being retaken at another size
  "row-action"  the green Download / Open / Join on a Contributor Worlds row,
                found by being the greenest thing on the right
  (x,y,w,h)     fractions of the image, for anything else
"""

# ------------------------------------------------------- host: set a world up

HOST = [
    dict(n=1, file="WorldShareOnTitleScreen.png", account="A",
         title="WorldShare adds a button to the title screen",
         marks=[dict(at="button:2", label="Everything starts here", side="left")]),

    dict(n=2, file="WorldShareInSinglePlayer.png", account="A",
         title="Open the world you want to share",
         marks=[dict(at="button:0", label="Pick your world, then Play",
                     side="above")]),

    dict(n=3, file="RunningWorldshareSetup.png", crop=(0.0, 0.84, 0.80, 0.16), account="A",
         title="Run /worldshare setup", marks=[]),

    dict(n=4, file="RanWorldShareSetup.png", crop="chat", account="A",
         title="Click the link to sign in to Google", label_size=60,
         # Fractions of the CROPPED chat panel, not the whole window.
         marks=[dict(at=(0.008, 0.637, 0.395, 0.115), label="Click this",
                     side="right")]),

    # Still in Minecraft, not the browser - this is the game's own "open a
    # link?" guard, so it carries the player's badge, not Drive's.
    dict(n=5, file="WorldShare1stAuthorizationForSetup.png", account="A",
         title="Minecraft asks before opening the link",
         marks=[dict(at="button:0", label="Click Yes", side="below")]),

    dict(n=6, file="Worldshare2ndAuthorizationForSetup.png", account="drive",
         title="Choose your Google account",
         marks=[dict(at=(0.522, 0.528, 0.440, 0.108),
                     label="Pick your account\n(this one's mine)", side="left")]),

    dict(n=7, file="Worldshare3rdAuthorizationForSetup.png", account="drive",
         title="Approve it",
         # The card overruns "share data safely" - which is a link in a
         # screenshot, so nobody was going to click it anyway.
         marks=[dict(at=(0.750, 0.864, 0.215, 0.070), label="Click here",
                     side="above")]),

    dict(n=8, file="WorldShareSetupAuthorized.png", account="drive",
         title="Done - go back to Minecraft", marks=[]),

    dict(n=9, file="WorldShareSetupProgressBarInGame.png", crop=(0.12, 0.43, 0.76, 0.32), account="A",
         title="WorldShare builds the world's files - about half a minute",
         marks=[]),

    dict(n=10, file="WorldshareSetupSuccessfulChatMessage.png", account="A",
         title="Setup is finished", marks=[]),

    dict(n=11, file="DriveBeforeRunningWorldshareSetup.png", crop=(0.0, 0.0, 0.56, 0.52), account="drive",
         title="Your Drive before", marks=[]),

    dict(n=12, file="DriveAfterRunningWorldshareSetup.png", crop=(0.0, 0.0, 0.56, 0.52), account="drive",
         title="And after - WorldShare made its own folder", marks=[]),

    dict(n=13, file="WorldShareSaveAndUploadMenu.png", account="A",
         title="Quitting uploads the world",
         marks=[dict(at="button:8", label="This replaces Save and Quit", side="below")]),

    dict(n=14, file="UploadProgressBar.png", account="A",
         title="Your world uploads to Drive", marks=[]),

    dict(n=15, file="ContributorWorldsAfterSetup.png", account="A",
         title="The world is ready to share", marks=[]),

    dict(n=16, file="RunningWorldshareHost.png", crop=(0.0, 0.84, 0.80, 0.16), account="A",
         title="Optional: /worldshare host opens it for live co-op", marks=[]),

    dict(n=17, file="WorldShareRanWorldshareHost.png", crop="chat", account="A",
         title="Your friend can now join you directly", marks=[]),
]

# ------------------------------------------ guest: get invited, then join

GUEST = [
    # The host's screen first - this is where the link the guest needs comes from.
    dict(n=1, file="RunningWorldshareInvite.png", crop=(0.0, 0.84, 0.80, 0.16), account="A",
         title="The host runs /worldshare invite with your email", marks=[]),

    dict(n=2, file="RanWorldshareInvite.png", crop="chat", account="A",
         title="Google shares the folder, and prints the link to send you",
         marks=[]),

    dict(n=3, file="RunningWorldshareInviteWithNoEmail.png", crop=(0.0, 0.35, 0.93, 0.53), account="A",
         title="Or /worldshare invite alone, just for the link", marks=[]),

    # Handover.
    dict(n=4, file="ContributorWorldsWithNoWorlds.png", account="B",
         title="On your machine: Contributor Worlds, then Add World",
         marks=[dict(at="button:0", label="Click this", side="right")]),

    dict(n=5, file="PutInLinkForSharedWorld.png", account="B",
         title="Paste the link, then sign in",
         marks=[
             # The URL box is not one of Minecraft's grey widgets, so
             # find_buttons cannot see it - hence the explicit rect. button:0
             # is "Sign in and pick world files"; button:1 is Cancel.
             dict(at=(0.105, 0.424, 0.803, 0.088), label="Paste the link here",
                  side="above"),
             # Ring only. There is no room for a card beside this button -
             # left and right both overflow, and below covers Cancel - and the
             # button already says what it does.
             dict(at="button:0"),
         ]),

    dict(n=6, file="WorldShareAuthStep1ForJoiningWorld.png", account="drive",
         title="Sign in with your own Google account", marks=[]),

    dict(n=7, file="WorldshareAuthStep2ForJoiningWorld.png", account="drive",
         title="Approve access", marks=[]),

    dict(n=8, file="WorldshareAuthStep3ForJoiningWorld.png", account="drive",
         title="Double-click the folder to open it",
         marks=[dict(at=(0.016, 0.250, 0.180, 0.335),
                     label="Double-click to open", side="right")]),

    dict(n=9, file="WorldshareAuthStep4ForJoiningWorld.png", account="drive",
         title="Select every file, then Insert",
         # Both cards point up into the empty half of the picker. Sending the
         # Insert one left jammed it into the bottom-right corner, where it
         # collided with its own ring and the shot edge.
         marks=[
             # A note, not a target - "18 selected" is a readout, and ringing
             # it implied it was something to click. The `at` still positions
             # the card; it just no longer draws a ring or an arrow.
             dict(at=(0.014, 0.959, 0.074, 0.028), ring=False, arrow=False,
                  label="Ctrl+A selects them all" + chr(10)
                        + "every file is needed",
                  side="above"),
             dict(at=(0.944, 0.954, 0.045, 0.034), label="Then Insert",
                  side="above", gap=110),
         ]),

    # Two rows, one slide: what you see depends on whether e4mc is installed.
    dict(n=10, file="WorldshareDownloadAfterSubscribingToWorld.png", account="B", crop="row",
         title="The world appears - what you can do depends on e4mc",
         pair="WorldshareLiveExample.png",
         caption_a="Without e4mc, or nobody hosting: download it and play later",
         caption_b="With e4mc, while they host: join them right now",
         # No labels on these two: the buttons already read Download and Join,
         # and a card repeating the word only covers the row's subtitle. The
         # ring draws the eye, the caption underneath says what it means.
         marks=[dict(at="row-action")],
         pair_marks=[dict(at="row-action")]),

    dict(n=11, file="WorldshareDownloadingProgressBar.png", crop="chat", account="B",
         title="Downloading - once only, then it stays", marks=[]),

    dict(n=12, file="WorldshareWorldOpenExample.png", account="B", crop="row",
         title="Open it and play",
         marks=[dict(at="row-action", label="Click Open", side="left")]),

    dict(n=13, file="WorldshareLockedExample.png", account="B", crop="row",
         title="If they are playing, the world is locked until they finish",
         marks=[]),
]

SPEC = HOST + GUEST
