package com.noop.ui.friends

import android.app.Activity
import android.os.Bundle
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent

/**
 * Where an invite link (`renoop://friends/add?c=…`) lands. It draws nothing: the code is handed to
 * [FriendsInviteLinks] and the app brought forward by its own launch intent, as a tap on a widget brings
 * it forward, so a link followed from a browser never stacks a second copy of the app's one activity on
 * the running one. Nobody is added here: the Friends tab asks first.
 *
 * A link opened before setup is done is dropped, as every other link into the app is: the terms and
 * onboarding gates come first, and a link kept through them would fire long after the tap.
 */
class FriendsInviteLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && NoopPrefs.of(this).getBoolean(NoopPrefs.KEY_ONBOARDED, false)) {
            FriendsInviteLinks.deliver(intent)
        }
        runCatching { startActivity(appLaunchIntent(this)) }
        finish()
    }
}
