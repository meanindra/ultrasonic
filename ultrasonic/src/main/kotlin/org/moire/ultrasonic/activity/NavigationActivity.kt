package org.moire.ultrasonic.activity

import android.app.AlertDialog
import android.app.SearchManager
import android.content.Intent
import android.content.res.Resources
import android.media.AudioManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.provider.SearchRecentSuggestions
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import org.koin.android.ext.android.inject
import org.koin.android.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.isOffline
import org.moire.ultrasonic.domain.PlayerState
import org.moire.ultrasonic.fragment.ServerSettingsModel
import org.moire.ultrasonic.provider.SearchSuggestionProvider
import org.moire.ultrasonic.service.DownloadFile
import org.moire.ultrasonic.service.MediaPlayerController
import org.moire.ultrasonic.service.MediaPlayerLifecycleSupport
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.FileUtil
import org.moire.ultrasonic.util.NowPlayingEventDistributor
import org.moire.ultrasonic.util.NowPlayingEventListener
import org.moire.ultrasonic.util.SubsonicUncaughtExceptionHandler
import org.moire.ultrasonic.util.Util
import timber.log.Timber


/**
 * The main Activity of Ultrasonic which loads all other screens as Fragments
 */
class NavigationActivity : AppCompatActivity() {
    var chatMenuItem: MenuItem? = null
    var bookmarksMenuItem: MenuItem? = null
    var sharesMenuItem: MenuItem? = null
    var podcastsMenuItem: MenuItem? = null
    private var theme: String? = null
    var nowPlayingView: FragmentContainerView? = null
    var nowPlayingHidden = false

    private lateinit var appBarConfiguration : AppBarConfiguration
    private lateinit var nowPlayingEventListener : NowPlayingEventListener

    private val serverSettingsModel: ServerSettingsModel by viewModel()
    private val lifecycleSupport: MediaPlayerLifecycleSupport by inject()
    private val mediaPlayerController: MediaPlayerController by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()
    private val nowPlayingEventDistributor: NowPlayingEventDistributor by inject()

    private var infoDialogDisplayed = false
    private var currentFragmentId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setUncaughtExceptionHandler()
        Util.applyTheme(this)

        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.navigation_activity)
        nowPlayingView = findViewById(R.id.now_playing_fragment)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val host: NavHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment? ?: return

        val navController = host.navController

        val drawerLayout : DrawerLayout? = findViewById(R.id.drawer_layout)
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.mainFragment, R.id.selectArtistFragment, R.id.searchFragment,
                R.id.playlistsFragment, R.id.sharesFragment, R.id.bookmarksFragment,
                R.id.chatFragment, R.id.podcastFragment, R.id.settingsFragment,
                R.id.aboutFragment, R.id.playerFragment),
            drawerLayout)

        setupActionBar(navController, appBarConfiguration)

        setupNavigationMenu(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val dest: String = try {
                resources.getResourceName(destination.id)
            } catch (e: Resources.NotFoundException) {
                Integer.toString(destination.id)
            }
            Timber.d("Navigated to $dest")

            currentFragmentId = destination.id
            // Handle the hiding of the NowPlaying fragment when the Player is active
            if (currentFragmentId == R.id.playerFragment) {
                hideNowPlaying()
            } else {
                if (!nowPlayingHidden) showNowPlaying()
            }

            // Hides menu items for Offline mode
            setMenuForServerSetting()

            // TODO: Maybe we can find a better place for theme change. Currently the change occurs when navigating between fragments
            // but theoretically Settings could request a Navigation Activity recreate instantly when the theme setting changes
            // Make sure to update theme if it has changed
            if (theme == null) theme = Util.getTheme(this)
            else if (theme != Util.getTheme(this)) {
                theme = Util.getTheme(this)
                recreate()
            }
        }

        // Determine first run and migrate server settings to DB as early as possible
        var showWelcomeScreen = Util.isFirstRun(this)
        val areServersMigrated: Boolean = serverSettingsModel.migrateFromPreferences()

        // If there are any servers in the DB, do not show the welcome screen
        showWelcomeScreen = showWelcomeScreen and !areServersMigrated

        loadSettings()
        showInfoDialog(showWelcomeScreen)

        nowPlayingEventListener = object : NowPlayingEventListener {
            override fun onDismissNowPlaying() {
                nowPlayingHidden = true;
                hideNowPlaying();
            }

            override fun onHideNowPlaying() {
                hideNowPlaying()
            }

            override fun onShowNowPlaying() {
                showNowPlaying()
            }
        }

        nowPlayingEventDistributor.subscribe(nowPlayingEventListener)
    }

    override fun onResume() {
        super.onResume()

        setMenuForServerSetting()
        Util.registerMediaButtonEventReceiver(this, false)
        // Lifecycle support's constructor registers some event receivers so it should be created early
        lifecycleSupport.onCreate()

        if (!nowPlayingHidden) showNowPlaying()
        else hideNowPlaying()
    }

    override fun onDestroy() {
        super.onDestroy()
        Util.unregisterMediaButtonEventReceiver(this, false)
        nowPlayingEventDistributor.unsubscribe(nowPlayingEventListener)
        imageLoaderProvider.clearImageLoader()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolumeDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val isVolumeUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolumeAdjust = isVolumeDown || isVolumeUp
        val isJukebox = mediaPlayerController.isJukeboxEnabled
        if (isVolumeAdjust && isJukebox) {
            mediaPlayerController.adjustJukeboxVolume(isVolumeUp)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupNavigationMenu(navController: NavController) {
        val sideNavView = findViewById<NavigationView>(R.id.nav_view)
        sideNavView?.setupWithNavController(navController)

        // The exit menu is handled here manually
        val exitItem: MenuItem = sideNavView.menu.findItem(R.id.menu_exit)
        exitItem.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_exit) {
                setResult(Constants.RESULT_CLOSE_ALL)
                mediaPlayerController.stopJukeboxService()
                imageLoaderProvider.getImageLoader().stopImageLoader()
                finish()
                exit()
            }
            true
        }

        chatMenuItem = sideNavView.menu.findItem(R.id.chatFragment)
        bookmarksMenuItem = sideNavView.menu.findItem(R.id.bookmarksFragment)
        sharesMenuItem = sideNavView.menu.findItem(R.id.sharesFragment)
        podcastsMenuItem = sideNavView.menu.findItem(R.id.podcastFragment)
    }

    private fun setupActionBar(navController: NavController, appBarConfig: AppBarConfiguration) {
        setupActionBarWithNavController(navController, appBarConfig)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val retValue = super.onCreateOptionsMenu(menu)
        val navigationView = findViewById<NavigationView>(R.id.nav_view)
        if (navigationView == null) {
            menuInflater.inflate(R.menu.navigation, menu)
            return true
        }
        return retValue
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return item.onNavDestinationSelected(findNavController(R.id.nav_host_fragment))
                || super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        return findNavController(R.id.nav_host_fragment).navigateUp(appBarConfiguration)
    }

    // TODO: Test if this works with external Intents
    // android.intent.action.SEARCH and android.media.action.MEDIA_PLAY_FROM_SEARCH calls here
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        val query = intent?.getStringExtra(SearchManager.QUERY)

        if (query != null) {
            val autoPlay = intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
            val suggestions = SearchRecentSuggestions(this, SearchSuggestionProvider.AUTHORITY, SearchSuggestionProvider.MODE)
            suggestions.saveRecentQuery(query, null)

            val bundle = Bundle()
            bundle.putString(Constants.INTENT_EXTRA_NAME_QUERY, query)
            bundle.putBoolean(Constants.INTENT_EXTRA_NAME_AUTOPLAY, autoPlay)
            findNavController(R.id.nav_host_fragment).navigate(R.id.searchFragment, bundle)
        }
    }

    private fun loadSettings() {
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        val preferences = Util.getPreferences(this)
        if (!preferences.contains(Constants.PREFERENCES_KEY_CACHE_LOCATION)) {
            val editor = preferences.edit()
            editor.putString(Constants.PREFERENCES_KEY_CACHE_LOCATION, FileUtil.getDefaultMusicDirectory(this).path)
            editor.apply()
        }
    }

    private fun exit() {
        lifecycleSupport.onDestroy()
        Util.unregisterMediaButtonEventReceiver(this, false)
        finish()
    }

    private fun showInfoDialog(show: Boolean) {
        if (!infoDialogDisplayed) {
            infoDialogDisplayed = true
            if (show) {
                AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setTitle(R.string.main_welcome_title)
                    .setMessage(R.string.main_welcome_text)
                    .setPositiveButton(R.string.common_ok) { dialog, i ->
                        dialog.dismiss()
                        findNavController(R.id.nav_host_fragment).navigate(R.id.settingsFragment)
                    }.show()
            }
        }
    }

    private fun setUncaughtExceptionHandler() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        if (handler !is SubsonicUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(SubsonicUncaughtExceptionHandler(this))
        }
    }

    private fun showNowPlaying() {
        if (!Util.getShowNowPlayingPreference(this)) {
            hideNowPlaying()
            return
        }

        // The logic for nowPlayingHidden is that the user can dismiss NowPlaying with a gesture,
        // and when the MediaPlayerService requests that it should be shown, it returns
        nowPlayingHidden = false;
        // Do not show for Player fragment
        if (currentFragmentId == R.id.playerFragment) {
            hideNowPlaying()
            return
        }

        if (nowPlayingView != null) {
            val playerState: PlayerState = mediaPlayerController.playerState
            if (playerState == PlayerState.PAUSED || playerState == PlayerState.STARTED) {
                val file: DownloadFile? = mediaPlayerController.currentPlaying
                if (file != null) {
                    val song = file.song
                    nowPlayingView?.visibility = View.VISIBLE
                }
            } else {
                hideNowPlaying()
            }
        }
    }

    private fun hideNowPlaying() {
        nowPlayingView?.visibility = View.GONE
    }

    private fun setMenuForServerSetting() {
        val visibility = !isOffline(this)
        chatMenuItem?.isVisible = visibility
        bookmarksMenuItem?.isVisible = visibility
        sharesMenuItem?.isVisible = visibility
        podcastsMenuItem?.isVisible = visibility
    }
}
