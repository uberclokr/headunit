package com.xterra.helm

import android.app.Application
import android.content.Intent
import com.xterra.helm.can.CanRepository
import com.xterra.helm.cameras.CameraRegistry
import com.xterra.helm.media.MediaRepository
import com.xterra.helm.power.BatteryRepository
import com.xterra.helm.sdr.SdrRepository
import com.xterra.helm.system.HomeLinkRepository
import com.xterra.helm.system.VehicleService

class HelmApp : Application() {

    // Simple service-locator; swap for Hilt if the project grows.
    lateinit var can: CanRepository
        private set
    lateinit var media: MediaRepository
        private set
    lateinit var cameras: CameraRegistry
        private set
    lateinit var sdr: SdrRepository
        private set
    lateinit var battery: BatteryRepository
        private set
    lateinit var homeLink: HomeLinkRepository
        private set
    lateinit var viofo: com.xterra.helm.cameras.ViofoLocator
        private set
    lateinit var clips: com.xterra.helm.cameras.ViofoClips
        private set
    lateinit var gps: com.xterra.helm.nav.GpsRepository
        private set
    lateinit var poi: com.xterra.helm.nav.PoiStore
        private set
    lateinit var settings: com.xterra.helm.system.SettingsRepository
        private set
    lateinit var net: com.xterra.helm.system.NetRepository
        private set
    lateinit var tilt: com.xterra.helm.system.TiltRepository
        private set
    lateinit var lora: com.xterra.helm.lora.LoraRepository
        private set
    lateinit var nav: com.xterra.helm.nav.route.NavRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        can = CanRepository(this)
        media = MediaRepository(this)
        cameras = CameraRegistry(this)
        sdr = SdrRepository(this)
        battery = BatteryRepository(this)
        homeLink = HomeLinkRepository()
        viofo = com.xterra.helm.cameras.ViofoLocator(cameras)
        clips = com.xterra.helm.cameras.ViofoClips(this)
        gps = com.xterra.helm.nav.GpsRepository(this)
        poi = com.xterra.helm.nav.PoiStore(this)
        settings = com.xterra.helm.system.SettingsRepository(this)
        net = com.xterra.helm.system.NetRepository(this, settings)
        tilt = com.xterra.helm.system.TiltRepository(this, settings)
        lora = com.xterra.helm.lora.LoraRepository(this)
        nav = com.xterra.helm.nav.route.NavRepository(this)
        // Kick the always-on vehicle service (CAN poll + reverse watch).
        startForegroundService(Intent(this, VehicleService::class.java))
    }

    companion object {
        lateinit var instance: HelmApp
            private set
    }
}
