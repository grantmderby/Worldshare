"""
What to mark on each screenshot, in the order a player meets it.

Two decks, because hosting and joining are different jobs done by different
people, and one combined sequence would have half of it irrelevant to whoever is
reading. Numbering restarts for the guest deck.

A `at` of "button:N" is resolved by finding Minecraft's buttons on the page and
counting top-to-bottom, left-to-right - so it keeps working if a shot is retaken
at another resolution. Anything that is not a button uses fractions of the image
instead, for the same reason.
"""

# ---------------------------------------------------------------- host deck

HOST = [
    dict(n=1, file="WorldShareOnTitleScreen.png", account="A", title="WorldShare adds a button to the title screen", marks=[
        dict(at="button:2", label="WorldShare lives here", side="left"),
    ]),
    dict(n=2, file="WorldShareInSinglePlayer.png", account="A", title="Open the world you want to share", marks=[]),
    dict(n=3, file="RunningWorldshareSetup.png", account="A", title="Run /worldshare setup", marks=[]),
    dict(n=4, file="RanWorldShareSetup.png", account="A", title="Setup starts and asks you to sign in", marks=[]),
    dict(n=5, file="WorldShare1stAuthorizationForSetup.png", account="drive", title="Choose your Google account", marks=[]),
    dict(n=6, file="Worldshare2ndAuthorizationForSetup.png", account="drive", title="Google asks what WorldShare may access", marks=[]),
    dict(n=7, file="Worldshare3rdAuthorizationForSetup.png", account="drive", title="Approve access to Drive", marks=[]),
    dict(n=8, file="WorldShareSetupAuthorized.png", account="drive", title="Authorized - return to Minecraft", marks=[]),
    dict(n=9, file="WorldShareSetupProgressBarInGame.png", account="A", title="WorldShare creates the world's files", marks=[]),
    dict(n=10, file="WorldshareSetupSuccessfulChatMessage.png", account="A", title="Setup is done", marks=[]),
    dict(n=11, file="DriveBeforeRunningWorldshareSetup.png", account="drive", title="Your Drive before setup", marks=[]),
    dict(n=12, file="DriveAfterRunningWorldshareSetup.png", account="drive", title="And after - WorldShare made its own folder", marks=[]),
    dict(n=13, file="RunningWorldshareInvite.png", account="A", title="Invite someone by email", marks=[]),
    dict(n=14, file="RanWorldshareInvite.png", account="A", title="They now have access, and you have the link", marks=[]),
    dict(n=15, file="RunningWorldshareInviteWithNoEmail.png", account="A", title="No email? Just get the link", marks=[]),
    dict(n=16, file="WorldShareSaveAndUploadMenu.png", account="A", title="The pause menu uploads when you quit", marks=[]),
    dict(n=17, file="UploadProgressBar.png", account="A", title="Your world uploads", marks=[]),
    dict(n=18, file="ContributorWorldsAfterSetup.png", account="A", title="The world is now listed and ready", marks=[]),
]

# --------------------------------------------------------------- guest deck

GUEST = [
    dict(n=1, file="ContributorWorldsWithNoWorlds.png", account="B", title="Start with an empty list", marks=[
        dict(at="button:0", label="Start here", side="right"),
    ]),
    dict(n=2, file="PutInLinkForSharedWorld.png", account="B", title="Paste the link they sent you", marks=[]),
    dict(n=3, file="WorldShareAuthStep1ForJoiningWorld.png", account="drive", title="Sign in with your own Google account", marks=[]),
    dict(n=4, file="WorldshareAuthStep2ForJoiningWorld.png", account="drive", title="Approve access", marks=[]),
    dict(n=5, file="WorldshareAuthStep3ForJoiningWorld.png", account="drive", title="Google shows the world's files", marks=[]),
    dict(n=6, file="WorldshareAuthStep4ForJoiningWorld.png", account="drive", title="Select every file in the folder", marks=[]),
    dict(n=7, file="WorldshareDownloadAfterSubscribingToWorld.png", account="B", title="The world appears - download it", marks=[]),
    dict(n=8, file="WorldshareDownloadingProgressBar.png", account="B", title="Downloading", marks=[]),
    dict(n=9, file="WorldshareWorldOpenExample.png", account="B", title="Open it and play", marks=[]),
]

# ------------------------------------------------- states worth knowing

STATES = [
    dict(n=1, file="WorldshareLockedExample.png", account="B", title="When the other player has it open", marks=[]),
    dict(n=2, file="RunningWorldshareHost.png", account="A", title="Run /worldshare host for live co-op", marks=[]),
    dict(n=3, file="WorldShareRanWorldshareHost.png", account="A", title="The world is now open to your friend", marks=[]),
    dict(n=4, file="WorldshareLiveExample.png", account="B", title="Their row goes LIVE - click Join", marks=[]),
]

SPEC = HOST + GUEST + STATES
