package com.mapbox.navigation.examples.androidauto.car

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.androidauto.MapboxCarNavigationManager
import com.mapbox.androidauto.car.MainCarContext
import com.mapbox.androidauto.car.MainScreenManager
import com.mapbox.androidauto.car.map.widgets.compass.CarCompassSurfaceRenderer
import com.mapbox.androidauto.car.map.widgets.logo.CarLogoSurfaceRenderer
import com.mapbox.androidauto.car.permissions.NeedsLocationPermissionsScreen
import com.mapbox.androidauto.deeplink.GeoDeeplinkNavigateAction
import com.mapbox.androidauto.internal.logAndroidAuto
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.requireMapboxNavigation
import com.mapbox.navigation.core.trip.session.TripSessionState
import com.mapbox.navigation.examples.androidauto.CarAppSyncComponent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(MapboxExperimental::class, ExperimentalPreviewMapboxNavigationAPI::class)
class MainCarSession : Session() {

    private var mainCarContext: MainCarContext? = null
    private lateinit var mainScreenManager: MainScreenManager
    private lateinit var mapboxCarMap: MapboxCarMap
    private lateinit var navigationManager: MapboxCarNavigationManager
    private val replayRouteTripSession = ReplayRouteTripSession()
    private val mainCarMapLoader = MainCarMapLoader()
    private val mapboxNavigation by requireMapboxNavigation()

    init {
        MapboxNavigationApp.attach(this)
        CarAppSyncComponent.getInstance().setCarSession(this)

        val logoSurfaceRenderer = CarLogoSurfaceRenderer()
        val compassSurfaceRenderer = CarCompassSurfaceRenderer()
        logAndroidAuto("MainCarSession constructor")
        lifecycle.addObserver(object : DefaultLifecycleObserver {

            override fun onCreate(owner: LifecycleOwner) {
                logAndroidAuto("MainCarSession onCreate")
                val mapInitOptions = MapInitOptions(
                    context = carContext,
                    styleUri = mainCarMapLoader.mapStyleUri(carContext.isDarkMode)
                )
                mapboxCarMap = MapboxCarMap(mapInitOptions)
                mainCarContext = MainCarContext(carContext, mapboxCarMap)
                mainScreenManager = MainScreenManager(mainCarContext!!)
                navigationManager = MapboxCarNavigationManager(carContext)
                observeScreenManager()
                observeAutoDrive()
            }

            override fun onStart(owner: LifecycleOwner) {
                MapboxNavigationApp.registerObserver(navigationManager)
            }

            override fun onResume(owner: LifecycleOwner) {
                logAndroidAuto("MainCarSession onResume")
                mapboxCarMap.registerObserver(logoSurfaceRenderer)
                mapboxCarMap.registerObserver(compassSurfaceRenderer)
            }

            override fun onPause(owner: LifecycleOwner) {
                logAndroidAuto("MainCarSession onPause")
                mapboxCarMap.unregisterObserver(logoSurfaceRenderer)
                mapboxCarMap.unregisterObserver(compassSurfaceRenderer)
            }

            override fun onStop(owner: LifecycleOwner) {
                logAndroidAuto("MainCarSession onStop")
                MapboxNavigationApp.unregisterObserver(navigationManager)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                logAndroidAuto("MainCarSession onDestroy")
                mainCarContext = null
            }
        })
    }

    private fun observeScreenManager() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainScreenManager.observeCarAppState()
            }
        }
    }

    private fun observeAutoDrive() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigationManager.autoDriveEnabledFlow.collect { isAutoDriveEnabled ->
                    val hasLocationPermissions = hasLocationPermission()
                    logAndroidAuto("MainCarSession onStart and hasLocationPermissions $hasLocationPermissions")
                    if (hasLocationPermissions) {
                        startTripSession(isAutoDriveEnabled)
                    }
                }
            }
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        logAndroidAuto("MainCarSession onCreateScreen")
        return when (hasLocationPermission()) {
            false -> NeedsLocationPermissionsScreen(carContext)
            true -> mainScreenManager.currentScreen()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTripSession(isAutoDriveEnabled: Boolean) {
        logAndroidAuto("MainCarSession startTripSession")
        if (isAutoDriveEnabled) {
            MapboxNavigationApp.registerObserver(replayRouteTripSession)
        } else {
            MapboxNavigationApp.unregisterObserver(replayRouteTripSession)
            if (mapboxNavigation.getTripSessionState() != TripSessionState.STARTED) {
                mapboxNavigation.startTripSession()
            }
        }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        logAndroidAuto("onCarConfigurationChanged ${carContext.isDarkMode}")

        mainCarMapLoader.updateMapStyle(carContext.isDarkMode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logAndroidAuto("onNewIntent $intent")

        val currentScreen: Screen = when (hasLocationPermission()) {
            false -> NeedsLocationPermissionsScreen(carContext)
            true -> {
                if (intent.action == CarContext.ACTION_NAVIGATE) {
                    mainCarContext?.let {
                        GeoDeeplinkNavigateAction(it, lifecycle).onNewIntent(intent)
                    }
                } else {
                    null
                }
            }
        } ?: mainScreenManager.currentScreen()
        carContext.getCarService(ScreenManager::class.java).push(currentScreen)
    }

    private fun hasLocationPermission(): Boolean {
        return PermissionsManager.areLocationPermissionsGranted(carContext)
    }
}
