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
    dict(n=1, file="WorldShareOnTitleScreen.png", marks=[
        dict(at="button:2", label="WorldShare lives here", side="left"),
    ]),
    dict(n=2, file="WorldShareInSinglePlayer.png", marks=[]),
    dict(n=3, file="RunningWorldshareSetup.png", marks=[]),
    dict(n=4, file="RanWorldShareSetup.png", marks=[]),
    dict(n=5, file="WorldShare1stAuthorizationForSetup.png", marks=[]),
    dict(n=6, file="Worldshare2ndAuthorizationForSetup.png", marks=[]),
    dict(n=7, file="Worldshare3rdAuthorizationForSetup.png", marks=[]),
    dict(n=8, file="WorldShareSetupAuthorized.png", marks=[]),
    dict(n=9, file="WorldShareSetupProgressBarInGame.png", marks=[]),
    dict(n=10, file="WorldshareSetupSuccessfulChatMessage.png", marks=[]),
    dict(n=11, file="DriveBeforeRunningWorldshareSetup.png", marks=[]),
    dict(n=12, file="DriveAfterRunningWorldshareSetup.png", marks=[]),
    dict(n=13, file="RunningWorldshareInvite.png", marks=[]),
    dict(n=14, file="RanWorldshareInvite.png", marks=[]),
    dict(n=15, file="RunningWorldshareInviteWithNoEmail.png", marks=[]),
    dict(n=16, file="WorldShareSaveAndUploadMenu.png", marks=[]),
    dict(n=17, file="UploadProgressBar.png", marks=[]),
    dict(n=18, file="ContributorWorldsAfterSetup.png", marks=[]),
]

# --------------------------------------------------------------- guest deck

GUEST = [
    dict(n=1, file="ContributorWorldsWithNoWorlds.png", marks=[
        dict(at="button:0", label="Start here", side="right"),
    ]),
    dict(n=2, file="PutInLinkForSharedWorld.png", marks=[]),
    dict(n=3, file="WorldShareAuthStep1ForJoiningWorld.png", marks=[]),
    dict(n=4, file="WorldshareAuthStep2ForJoiningWorld.png", marks=[]),
    dict(n=5, file="WorldshareAuthStep3ForJoiningWorld.png", marks=[]),
    dict(n=6, file="WorldshareAuthStep4ForJoiningWorld.png", marks=[]),
    dict(n=7, file="WorldshareDownloadAfterSubscribingToWorld.png", marks=[]),
    dict(n=8, file="WorldshareDownloadingProgressBar.png", marks=[]),
    dict(n=9, file="WorldshareWorldOpenExample.png", marks=[]),
]

# ------------------------------------------------- states worth knowing

STATES = [
    dict(n=1, file="WorldshareLockedExample.png", marks=[]),
    dict(n=2, file="RunningWorldshareHost.png", marks=[]),
    dict(n=3, file="WorldShareRanWorldshareHost.png", marks=[]),
    dict(n=4, file="WorldshareLiveExample.png", marks=[]),
]

SPEC = HOST + GUEST + STATES
