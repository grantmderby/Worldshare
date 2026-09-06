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
         title="Open the world you want to share", marks=[]),

    dict(n=3, file="RunningWorldshareSetup.png", account="A",
         title="Run /worldshare setup", marks=[]),

    dict(n=4, file="RanWorldShareSetup.png", account="A",
         title="Click the link to sign in to Google", marks=[]),

    dict(n=5, file="WorldShare1stAuthorizationForSetup.png", account="drive",
         title="Choose your Google account", marks=[]),

    dict(n=6, file="Worldshare2ndAuthorizationForSetup.png", account="drive",
         title="Google asks what WorldShare may access", marks=[]),

    dict(n=7, file="Worldshare3rdAuthorizationForSetup.png", account="drive",
         title="Approve it", marks=[]),

    dict(n=8, file="WorldShareSetupAuthorized.png", account="drive",
         title="Done - go back to Minecraft", marks=[]),

    dict(n=9, file="WorldShareSetupProgressBarInGame.png", account="A",
         title="WorldShare builds the world's files - about half a minute",
         marks=[]),

    dict(n=10, file="WorldshareSetupSuccessfulChatMessage.png", account="A",
         title="Setup is finished", marks=[]),

    dict(n=11, file="DriveBeforeRunningWorldshareSetup.png", account="drive",
         title="Your Drive before", marks=[]),

    dict(n=12, file="DriveAfterRunningWorldshareSetup.png", account="drive",
         title="And after - WorldShare made its own folder", marks=[]),

    dict(n=13, file="WorldShareSaveAndUploadMenu.png", account="A",
         title="Quitting uploads the world",
         marks=[dict(at="button:7", label="This replaces Save and Quit", side="right")]),

    dict(n=14, file="UploadProgressBar.png", account="A",
         title="Your world uploads to Drive", marks=[]),

    dict(n=15, file="ContributorWorldsAfterSetup.png", account="A",
         title="The world is ready to share", marks=[]),

    dict(n=16, file="RunningWorldshareHost.png", account="A",
         title="Optional: /worldshare host opens it for live co-op", marks=[]),

    dict(n=17, file="WorldShareRanWorldshareHost.png", account="A",
         title="Your friend can now join you directly", marks=[]),
]

# ------------------------------------------ guest: get invited, then join

GUEST = [
    # The host's screen first - this is where the link the guest needs comes from.
    dict(n=1, file="RunningWorldshareInvite.png", account="A",
         title="The host runs /worldshare invite with your email", marks=[]),

    dict(n=2, file="RanWorldshareInvite.png", account="A",
         title="Google shares the folder, and prints the link to send you",
         marks=[]),

    dict(n=3, file="RunningWorldshareInviteWithNoEmail.png", account="A",
         title="Or /worldshare invite alone, just for the link", marks=[]),

    # Handover.
    dict(n=4, file="ContributorWorldsWithNoWorlds.png", account="B",
         title="On your machine: Contributor Worlds, then Add World",
         marks=[dict(at="button:0", label="Click this", side="right")]),

    dict(n=5, file="PutInLinkForSharedWorld.png", account="B",
         title="Paste the link, then sign in",
         marks=[
             dict(at=(0.105, 0.428, 0.803, 0.083), label="Paste the link here",
                  side="above"),
             dict(at="button:0", label="Then click this", side="below"),
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
         marks=[
             dict(at=(0.020, 0.950, 0.095, 0.040),
                  label="Ctrl+A selects them all - every file is needed",
                  side="above"),
             dict(at=(0.941, 0.953, 0.052, 0.037), label="Then Insert",
                  side="left"),
         ]),

    # Two rows, one slide: what you see depends on whether e4mc is installed.
    dict(n=10, file="WorldshareDownloadAfterSubscribingToWorld.png", account="B",
         title="The world appears - what you can do depends on e4mc",
         pair="WorldshareLiveExample.png",
         caption_a="Without e4mc, or nobody hosting: download it and play later",
         caption_b="With e4mc, while they host: join them right now",
         # No labels on these two: the buttons already read Download and Join,
         # and a card repeating the word only covers the row's subtitle. The
         # ring draws the eye, the caption underneath says what it means.
         marks=[dict(at="row-action")],
         pair_marks=[dict(at="row-action")]),

    dict(n=11, file="WorldshareDownloadingProgressBar.png", account="B",
         title="Downloading - once only, then it stays", marks=[]),

    dict(n=12, file="WorldshareWorldOpenExample.png", account="B",
         title="Open it and play",
         marks=[dict(at="row-action", label="Click Open", side="left")]),

    dict(n=13, file="WorldshareLockedExample.png", account="B",
         title="If they are playing, the world is locked until they finish",
         marks=[]),
]

SPEC = HOST + GUEST
