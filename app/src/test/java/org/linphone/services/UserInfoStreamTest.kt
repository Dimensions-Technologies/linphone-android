package org.linphone.services

import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.models.AuthenticatedUser
import org.linphone.models.UserInfo

/**
 * Checks the shared user info stream in [UserService.userInfoStream].
 *
 * A failed fetch (for example when the app is started by a push with no network) used to end the
 * stream for the life of the process. Every screen then got an empty UserInfo, so the user was told
 * they had no permission to use the client, and signing in again didn't help (WI #28461).
 */
class UserInfoStreamTest {

    private val signedOut = AuthenticatedUser(AuthenticatedUser.UNINTIALIZED_AUTHENTICATEDUSER)
    private val alice = AuthenticatedUser("alice")
    private val bob = AuthenticatedUser("bob")

    private val authUsers = BehaviorSubject.createDefault(signedOut)

    /** Errors RxJava couldn't deliver, which crash the app outside of tests. */
    private val undeliverable = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        RxJavaPlugins.setErrorHandler { undeliverable.add(it) }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
        assertTrue("Undeliverable errors: $undeliverable", undeliverable.isEmpty())
    }

    private fun userInfoFor(id: String) = UserInfo(
        id = id,
        displayName = id,
        permissions = listOf("customer.user.uc.mobile")
    )

    private fun stream(fetch: suspend () -> UserInfo) =
        UserService.userInfoStream(authUsers, retryInitialDelayMs = 10, retryMaxDelayMs = 20, fetch)

    @Test
    fun failedFetchIsRetriedAndTheStreamStaysAlive() {
        val attempts = AtomicInteger()
        val user = stream {
            if (attempts.incrementAndGet() <= 2) throw IOException("Unable to resolve host")
            userInfoFor(authUsers.value!!.id)
        }

        val observer = user.test()
        authUsers.onNext(alice)

        observer.awaitCount(1)
        observer.assertValue { it.id == "alice" && it.hasClientPermission() }
        observer.assertNoErrors()
        observer.assertNotComplete()
        assertEquals(3, attempts.get())

        // The stream still follows sign-ins after the failures
        authUsers.onNext(signedOut)
        authUsers.onNext(bob)
        observer.awaitCount(2)
        assertEquals("bob", observer.values()[1].id)
    }

    @Test
    fun signingBackInAsTheSameUserFetchesAgain() {
        val fetches = AtomicInteger()
        val user = stream {
            fetches.incrementAndGet()
            userInfoFor(authUsers.value!!.id)
        }

        val observer = user.test()
        authUsers.onNext(alice)
        observer.awaitCount(1)

        authUsers.onNext(signedOut)
        authUsers.onNext(alice)
        observer.awaitCount(2)

        assertEquals(2, fetches.get())
    }

    @Test
    fun tokenUpdatesForTheSameUserDoNotFetchAgain() {
        val fetches = AtomicInteger()
        val user = stream {
            fetches.incrementAndGet()
            userInfoFor(authUsers.value!!.id)
        }

        val observer = user.test()
        authUsers.onNext(alice)
        observer.awaitCount(1)

        authUsers.onNext(AuthenticatedUser("alice"))
        authUsers.onNext(AuthenticatedUser("alice"))

        observer.assertValueCount(1)
        assertEquals(1, fetches.get())
    }

    @Test
    fun signedOutSubscribersGetNothing() {
        val user = stream { userInfoFor(authUsers.value!!.id) }

        val first = user.test()
        authUsers.onNext(alice)
        first.awaitCount(1)

        authUsers.onNext(signedOut)

        user.test().assertEmpty()
    }

    @Test
    fun signingInAsSomeoneElseDoesNotReplayThePreviousUser() {
        val bobFetched = CompletableDeferred<Unit>()
        val user = stream {
            val id = authUsers.value!!.id
            if (id == "bob") bobFetched.await()
            userInfoFor(id)
        }

        val first = user.test()
        authUsers.onNext(alice)
        first.awaitCount(1)

        authUsers.onNext(signedOut)
        authUsers.onNext(bob)

        // This is what LoginActivity does after sign-in: it must wait for Bob, not get Alice
        val afterSignIn = user.test()
        afterSignIn.await(100, TimeUnit.MILLISECONDS)
        afterSignIn.assertEmpty()

        bobFetched.complete(Unit)
        afterSignIn.awaitCount(1)
        afterSignIn.assertValue { it.id == "bob" }
    }

    @Test
    fun switchingUserWithoutSigningOutDoesNotReplayThePreviousUser() {
        val bobFetched = CompletableDeferred<Unit>()
        val user = stream {
            val id = authUsers.value!!.id
            if (id == "bob") bobFetched.await()
            userInfoFor(id)
        }

        val first = user.test()
        authUsers.onNext(alice)
        first.awaitCount(1)

        authUsers.onNext(bob)

        val afterSwitch = user.test()
        afterSwitch.await(100, TimeUnit.MILLISECONDS)
        afterSwitch.assertEmpty()

        bobFetched.complete(Unit)
        afterSwitch.awaitCount(1)
        afterSwitch.assertValue { it.id == "bob" }
    }
}
