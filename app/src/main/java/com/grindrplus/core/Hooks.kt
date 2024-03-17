package com.grindrplus.core

import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import com.grindrplus.Hooker
import com.grindrplus.core.Constants.NUM_OF_COLUMNS
import com.grindrplus.core.Constants.Returns.RETURN_FALSE
import com.grindrplus.core.Constants.Returns.RETURN_INTEGER_MAX_VALUE
import com.grindrplus.core.Constants.Returns.RETURN_LONG_MAX_VALUE
import com.grindrplus.core.Constants.Returns.RETURN_TRUE
import com.grindrplus.core.Constants.Returns.RETURN_UNIT
import com.grindrplus.core.Constants.Returns.RETURN_ZERO
import com.grindrplus.core.Obfuscation.GApp
import com.grindrplus.core.Utils.findHeightAndWeightTextViews
import com.grindrplus.core.Utils.logChatMessage
import com.grindrplus.core.Utils.mapFeatureFlag
import com.grindrplus.decorated.persistence.model.AlbumContent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.hookAllConstructors
import de.robv.android.xposed.XposedBridge.hookMethod
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.findAndHookConstructor
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.XposedHelpers.findConstructorExact
import de.robv.android.xposed.XposedHelpers.findMethodExact
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt
import kotlin.time.Duration


object Hooks {
    var ownProfileId: String? = null
    var chatMessageReceivedPluginManager: Any? = null
    /**
     * Hook the app updates to prevent the app from updating.
     * Also spoof the app version with the latest version to
     * prevent the API from detecting that the app is outdated.
     *
     * Inspired by @Tebbe's initial idea.
     */
    fun hookUpdateInfo(name: String, code: Int) {
        // `updateAvailability`, as the name suggests, is called to check
        // if there is an update available. We return UPDATE_NOT_AVAILABLE.
        findAndHookMethod(
            "com.google.android.play.core.appupdate.AppUpdateInfo",
            Hooker.pkgParam.classLoader, "updateAvailability",
            Constants.Returns.RETURN_ONE // In this specific scenario, 1 means "no updates".
        )

        if (Constants.GRINDR_PKG_VERSION_NAME.compareTo(name) < 0) {
            // The constructor of `AppConfiguration` has 3 different fields used
            // to store information regarding the versioning of the app. We use
            // the newly fetched version name and code to spoof the app version.
            Logger.xLog("Hooking update info with version $name ($code)")
            findAndHookConstructor(
                "com.grindrapp.android.base.config.AppConfiguration",
                Hooker.pkgParam.classLoader,
                findClass("com.grindrapp.android.base.config.AppConfiguration.b", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.base.config.AppConfiguration.f", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.base.config.AppConfiguration.d", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.base.config.AppConfiguration.e", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.base.config.AppConfiguration.c", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.base.config.AppConfiguration.a", Hooker.pkgParam.classLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        setObjectField(param.thisObject, "a", name)
                        setObjectField(param.thisObject, "b", code)
                        setObjectField(param.thisObject, "u", "$name.$code")
                    }
                }
            )
        }
    }

    /**
     * Store the `ChatMessageHandler` instance in a variable.
     * This will be used later on to send fake messages.
     */
    fun storechatMessageReceivedPluginManager() {
        hookAllConstructors(findClass(
            "w5.r",
            Hooker.pkgParam.classLoader
        ), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                chatMessageReceivedPluginManager = param.thisObject
            }
        })
    }

    /**
     * Hooks chat rest service to store the phrases locally.
     */
    fun localSavedPhrases() {
        val ChatRestServiceClass = findClass(
            GApp.api.ChatRestService, Hooker.pkgParam.classLoader)

        val PhrasesRestServiceClass = findClass(
            GApp.api.PhrasesRestService, Hooker.pkgParam.classLoader)

        val createSuccessResultConstructor = findConstructorExact(
            "c9.a\$b", Hooker.pkgParam.classLoader, Any::class.java)

        val AddSavedPhraseResponseConstructor = findConstructorExact(
            GApp.model.AddSavedPhraseResponse, Hooker.pkgParam.classLoader,
            String::class.java
        )

        val PhrasesResponseConstructor = findConstructorExact(
            GApp.model.PhrasesResponse, Hooker.pkgParam.classLoader, Map::class.java)

        val PhraseConstructor = findConstructorExact(
            GApp.persistence.model.Phrase,
            Hooker.pkgParam.classLoader,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        fun hookChatRestService(service: Any): Any {
            val invocationHandler = Proxy.getInvocationHandler(service)
            return Proxy.newProxyInstance(
                Hooker.pkgParam.classLoader,
                arrayOf(ChatRestServiceClass)) { proxy, method, args ->
                when (method.name) {
                    GApp.api.ChatRestService_.addSavedPhrase -> {
                        val phrase = getObjectField(args[0], "phrase") as String
                        val currentPhrases = Hooker.config.readMap("phrases")
                        var id = Hooker.config.readInt("id_counter", 0) + 1
                        while (currentPhrases.has(id.toString())) id++
                        currentPhrases.put(
                            id.toString(), JSONObject().apply {
                                put("text", phrase)
                                put("frequency", 0)
                                put("timestamp", 0)
                            }
                        )
                        Hooker.config.writeConfig("phrases", currentPhrases)
                        val response = AddSavedPhraseResponseConstructor.newInstance(id.toString())
                        createSuccessResultConstructor.newInstance(response)
                    }
                    GApp.api.ChatRestService_.deleteSavedPhrase -> {
                        val id = args[0] as String
                        val currentPhrases = Hooker.config.readMap("phrases")
                        currentPhrases.remove(id)
                        Hooker.config.writeConfig("phrases", currentPhrases)
                        createSuccessResultConstructor.newInstance(Unit)
                    }
                    GApp.api.ChatRestService_.increaseSavedPhraseClickCount -> {
                        val id = args[0] as String
                        val phrasesMap = Hooker.config.readMap("phrases")
                        phrasesMap.optJSONObject(id)?.apply {
                            val newFrequency = optInt("frequency", 0) + 1
                            put("frequency", newFrequency)
                        } ?: phrasesMap.put(id, JSONObject().put("frequency", 1))
                        Hooker.config.writeMap("phrases", phrasesMap)
                        createSuccessResultConstructor.newInstance(Unit)
                    }
                    else -> invocationHandler.invoke(proxy, method, args)
                }
            }
        }

        fun hookPhrasesRestService(service: Any): Any {
            val invocationHandler = Proxy.getInvocationHandler(service)
            return Proxy.newProxyInstance(
                Hooker.pkgParam.classLoader,
                arrayOf(PhrasesRestServiceClass)) { proxy, method, args ->
                when (method.name) {
                    GApp.api.PhrasesRestService_.getSavedPhrases -> {
                        val phrasesMap = Hooker.config.readMap("phrases")
                        val keysSequence = phrasesMap.keys().asSequence().toSet()
                        val phrases = keysSequence.associateWith { id ->
                            val phraseDetails = phrasesMap.optJSONObject(id)
                            val text = phraseDetails?.optString("text", "") ?: ""
                            val timestamp = phraseDetails?.optLong("timestamp", 0L) ?: 0L
                            val frequency = phraseDetails?.optInt("frequency", 0) ?: 0
                            PhraseConstructor.newInstance(id, text, timestamp, frequency)
                        }
                        val phrasesResponse = PhrasesResponseConstructor.newInstance(phrases)
                        createSuccessResultConstructor.newInstance(phrasesResponse)
                    }
                    else -> invocationHandler.invoke(proxy, method, args)
                }
            }
        }

        findAndHookMethod(
            "retrofit2.Retrofit",
            Hooker.pkgParam.classLoader,
            "create",
            Class::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.result
                    param.result = when {
                        ChatRestServiceClass.isInstance(service) -> hookChatRestService(service)
                        PhrasesRestServiceClass.isInstance(service) -> hookPhrasesRestService(service)
                        else -> service
                    }
                }
            }
        )
    }

    /**
     * Let the user scroll through unlimited profiles.
     */
    fun unlimitedProfiles() {
        // Enforce the usage of `InaccessibleProfileManager`, otherwise the app
        // will tell the user to restart the app to apply subscription changes.
        findAndHookMethod(
            GApp.profile.experiments.InaccessibleProfileManager,
            Hooker.pkgParam.classLoader,
            GApp.profile.experiments.InaccessibleProfileManager_.isProfileEnabled,
            RETURN_TRUE
        )

        // Remove all ads and upsells from the cascade (ServerDrivenCascadeCacheState)
        findAndHookMethod(
            "com.grindrapp.android.persistence.model.serverdrivencascade.ServerDrivenCascadeCacheState",
            Hooker.pkgParam.classLoader,
            "getItems",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    val items = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as List<*>
                    return items.filterNotNull().filter {
                        it.javaClass.name == "com.grindrapp.android.persistence.model.serverdrivencascade.ServerDrivenCascadeCachedProfile"
                    }
                }
            })

        findAndHookMethod(
            "com.grindrapp.android.persistence.model.serverdrivencascade.ServerDrivenCascadeCachedProfile",
            Hooker.pkgParam.classLoader,
            "getUpsellType",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            }
        )
    }

    /**
     * Allow screenshots in all the views of the application (including expiring photos, albums, etc.)
     *
     * Inspired in the project https://github.com/veeti/DisableFlagSecure
     * Credit and thanks to @veeti!
     */
    fun allowScreenshotsHook() {
        findAndHookMethod(
            Window::class.java,
            "setFlags",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    var flags = param.args[0] as Int
                    flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    param.args[0] = flags
                }
            })
    }

    /**
     * Allow Fake GPS in order to fake location.
     *
     * WARNING: Abusing this feature may result in a permanent ban on your Grindr account.
     */
    fun allowMockProvider() {
        val class_Location = findClass(
            "android.location.Location",
            Hooker.pkgParam.classLoader
        )

        findAndHookMethod(
            class_Location,
            "isFromMockProvider",
            RETURN_FALSE
        )

        class LocationMethodHook(private val getCoordinate: () -> Double?) : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (Hooker.config.readBoolean("teleport_enabled", false)) {
                    getCoordinate()?.let { spoofedCoordinate ->
                        param.result = spoofedCoordinate
                    }
                }
            }
        }

        findAndHookMethod(
            class_Location,
            "getLatitude",
            LocationMethodHook { Utils.getLocationPreference("teleport_location")?.first }
        )

        findAndHookMethod(
            class_Location,
            "getLongitude",
            LocationMethodHook { Utils.getLocationPreference("teleport_location")?.second }
        )

        if (Build.VERSION.SDK_INT >= 31) {
            findAndHookMethod(
                class_Location,
                "isMock",
                RETURN_FALSE
            )
        }
    }

    /**
     * Hook the current user plan so that we can benefit from
     * all the features of Grindr Unlimited.
     */
    fun hookUserSessionImpl() {
        val FeatureClass = findClass(
            GApp.model.Feature,
            Hooker.pkgParam.classLoader
        )

        val UserSessionClass = findClass(
            GApp.storage.UserSession,
            Hooker.pkgParam.classLoader
        )

        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.hasFeature_feature,
            FeatureClass,
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    val featuresReturningFalse = setOf("DisableScreenshot")
                    return (param.args[0].toString() !in featuresReturningFalse)
                }
            })

        // This method determines whether a user should be shown an upsell
        // for Xtra based on their current subscription status. If the user
        // already has Xtra or Unlimited (or their free versions), the upsell
        // will not be shown.
        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.isNoXtraUpsell,
            RETURN_TRUE
        )

        // This method determines whether a user should be shown an upsell
        // for Plus based on their current subscription status. If the user
        // already has Xtra or Unlimited (or their free versions), the upsell
        // will not be shown.
        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.isNoPlusUpsell,
            RETURN_TRUE
        )

        // This method checks whether the user has Free or not.
        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.isFree,
            RETURN_FALSE
        )

        // This method checks whether the user has Xtra or not.
        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.isXtra,
            RETURN_FALSE
        )

        // This method checks whether the user has Plus or not.
        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.isPlus,
            RETURN_FALSE
        )

        // This method checks whether the user has Unlimited or not.
        findAndHookMethod(
            UserSessionClass,
            GApp.storage.IUserSession_.isUnlimited,
            RETURN_TRUE
        )
    }

    /**
     * Grant all the Grindr features (except disabling screenshots).
     * A few more changes may be needed to use all the features.
     */
    fun hookFeatureGranting() {
        val FeatureClass = findClass(
            GApp.model.Feature,
            Hooker.pkgParam.classLoader
        )

        val FeatureFlagsClass = findClass(
            "w6.g",
            Hooker.pkgParam.classLoader
        )

        findAndHookMethod(
            FeatureClass,
            GApp.model.Feature_.isGranted,
            RETURN_TRUE
        )

        for (method in FeatureFlagsClass.declaredMethods) {
            when (method.name) {
                "isEnabled", "isDisabled" -> {
                    findAndHookMethod(
                        FeatureFlagsClass,
                        method.name,
                        object : XC_MethodReplacement() {
                            override fun replaceHookedMethod(param: MethodHookParam): Boolean {
                                val isEnabled = mapFeatureFlag(getObjectField(param.thisObject,
                                    "featureFlagName") as String, param)
                                return if (method.name == "isEnabled") isEnabled else !isEnabled
                            }
                        }
                    )
                }
            }
        }

        findAndHookConstructor(
            "com.grindrapp.android.ui.settings.distance.SettingDistanceVisibilityViewModel\$e",
            Hooker.pkgParam.classLoader,
            Int::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Set::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    param?.args?.set(4, false)
                }
            }
        )

        listOf(
            findClass(
                GApp.model.UpsellsV8,
                Hooker.pkgParam.classLoader
            ),

            findClass(
                GApp.model.Inserts,
                Hooker.pkgParam.classLoader
            )
        ).forEach { className ->
            findAndHookMethod(
                className,
                "getMpuFree",
                RETURN_INTEGER_MAX_VALUE
            )

            findAndHookMethod(
                className,
                "getMpuXtra",
                RETURN_ZERO
            )
        }
    }

    /**
     * Allow videocalls on empty chats: Grindr checks that both users have chatted with each other
     * (both must have sent at least one message to the other) in order to allow videocalls.
     *
     * This hook allows the user to bypass this restriction.
     */
    fun allowVideocallsOnEmptyChats() {
        findAndHookMethod(
            findClass("com.grindrapp.android.ui.chat.individual.IndividualChatNavViewModel", Hooker.pkgParam.classLoader),
            "d", // pseudoname: checkMessageForVideoCall
            String::class.java,
            String::class.java,
            findClass("kotlin.coroutines.Continuation", Hooker.pkgParam.classLoader),
            RETURN_TRUE
        )
    }

    /**
     * Hook online indicator duration:
     *
     * "After closing the app, the profile remains online for 10 minutes. It is misleading. People think that you are rude for not answering, when in reality you are not online."
     *
     * Now, you can limit the Online indicator (green dot) for a custom duration.
     *
     * Inspired in the suggestion made at:
     * https://grindr.uservoice.com/forums/912631-grindr-feedback/suggestions/34555780-more-accurate-online-status-go-offline-when-clos
     *
     * @param duration Duration in milliseconds.
     *
     * @see Duration
     * @see Duration.inWholeMilliseconds
     *
     * @author ElJaviLuki
     */
    fun hookOnlineIndicatorDuration(duration: Duration) {
        findAndHookMethod(findClass("zd.s0", Hooker.pkgParam.classLoader),
            // pseudoname: shouldShowOnlineIndicator
            "a", Long::class.javaPrimitiveType, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Boolean {
                    return System.currentTimeMillis() - (param.args[0] as Long) <= duration.inWholeMilliseconds
                }
            })
    }

    /**
     * Hook `ExpiringPhotoStatusResponse` to set `available` and
     * `total` to `Int.MAX_VALUE`. This way we can see all the
     * expiring photos without any restriction.
     */
    fun unlimitedExpiringPhotos() {
        val class_ExpiringPhotoStatusResponse = findClass(
            GApp.model.ExpiringPhotoStatusResponse,
            Hooker.pkgParam.classLoader
        )

        findAndHookMethod(
            class_ExpiringPhotoStatusResponse,
            GApp.model.ExpiringPhotoStatusResponse_.getTotal,
            RETURN_INTEGER_MAX_VALUE
        )

        findAndHookMethod(
            class_ExpiringPhotoStatusResponse,
            GApp.model.ExpiringPhotoStatusResponse_.getAvailable,
            RETURN_INTEGER_MAX_VALUE
        )
    }

    /**
     * Create a local cache storage to store valid AlbumContent objects.
     * This way, we can see all the contents of an album even if it is empty.
     */
    fun unlimitedAlbums() {
        findAndHookConstructor("com.grindrapp.android.model.Album", Hooker.pkgParam.classLoader,
            Long::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            findClass("com.grindrapp.android.model.PromoAlbumData", Hooker.pkgParam.classLoader),
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            findClass("com.grindrapp.android.model.ContentCount", Hooker.pkgParam.classLoader),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Ignore our own album(s)
                    val albumId = param.args[0] as Long
                    if (albumId == -1L) return

                    // Mark all albums as viewable
                    if (param.args[12] == false) param.args[12] = true

                    // When a user has more than six shared albums on Grindr, the app automatically
                    // deletes the contents of albums that are older than the sixth one. To prevent
                    // loss of this content, the workaround is to save the album contents to a local
                    // device and then restore them to the app as needed.
                    if ((param.args[5] as List<*>).size > 1) {
                        val contents = mutableListOf<AlbumContent>()
                        for (content in param.args[5] as List<*>) {
                            content?.let { AlbumContent.create(it) }?.
                            let { contents.add(it as AlbumContent) }
                        }
                        Hooker.globalCache.saveAlbumContents(albumId, contents)
                    }

                    if ((param.args[5] as List<*>).isEmpty()) {
                        val contents = Hooker.globalCache
                            .getAlbumContents(albumId)
                        if (contents != null) {
                            val albumContentsList = mutableListOf<Any>()
                            for (albumContent in contents) {
                                albumContentsList.add(
                                    AlbumContent.create(albumContent)
                                )
                            }
                            param.args[5] = albumContentsList
                        } else {
                            // Album wasn't cached and therefore we can't restore it
                            Logger.xLog("Album $albumId contents were not cached!")
                        }
                    }
                }
            }
        )
    }

    /**
     * Allow unlimited taps on profiles.
     *
     * @author ElJaviLuki
     */
    fun unlimitedTaps() {
        val class_TapsAnimLayout = findClass(
            GApp.view.TapsAnimLayout,
            Hooker.pkgParam.classLoader
        )

        // Reset taps on long press (allows using tap variants)
        findAndHookMethod(
            class_TapsAnimLayout,
            GApp.view.TapsAnimLayout_.getCanSelectVariants,
            RETURN_TRUE
        )

        findAndHookMethod(
            class_TapsAnimLayout,
            GApp.view.TapsAnimLayout_.getDisableVariantSelection,
            RETURN_FALSE
        )
    }

    /**
     * Hook the method that returns the duration of the expiring photos.
     * This way, the photos will not expire and you will be able to see them any time you want.
     *
     * @author ElJaviLuki
     */
    fun removeExpirationOnExpiringPhotos() {
        val class_ExpiringImageBody = findClass(
            GApp.model.ExpiringImageBody,
            Hooker.pkgParam.classLoader
        )

        findAndHookMethod(
            class_ExpiringImageBody,
            GApp.model.ExpiringImageBody_.getDuration,
            RETURN_LONG_MAX_VALUE
        )

        findAndHookMethod(
            class_ExpiringImageBody,
            GApp.model.ExpiringImageBody_.getViewsRemaining,
            RETURN_INTEGER_MAX_VALUE
        )
    }

    /**
     * Prevents people from knowing that you have seen their profile.
     */
    fun preventRecordProfileViews() {
        if (Hooker.config.readBoolean("dont_record_views", true)) {
            val ProfileRestServiceClass = findClass(
                GApp.api.ProfileRestService, Hooker.pkgParam.classLoader
            )

            val createSuccessResultConstructor = findConstructorExact(
                "c9.a\$b", Hooker.pkgParam.classLoader, Any::class.java
            )

            findAndHookMethod(
                "retrofit2.Retrofit",
                Hooker.pkgParam.classLoader,
                "create",
                Class::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val service = param.result
                        param.result = when {
                            ProfileRestServiceClass.isAssignableFrom(service.javaClass) -> {
                                val invocationHandler = Proxy.getInvocationHandler(service)
                                Proxy.newProxyInstance(
                                    Hooker.pkgParam.classLoader,
                                    arrayOf(ProfileRestServiceClass)
                                ) { proxy, method, args ->
                                    if (method.name in arrayOf(
                                            GApp.api.ProfileRestService_.logView,
                                            GApp.api.ProfileRestService_.logViews
                                        )
                                    ) {
                                        createSuccessResultConstructor.newInstance(Unit)
                                    } else {
                                        invocationHandler.invoke(proxy, method, args)
                                    }
                                }
                            }

                            else -> service
                        }
                    }
                }
            )
        }
    }

    /**
     * Prevents the app from deleting messages / chats when someone
     * blocks you.
     */
    fun keepChatsOfBlockedProfiles() {
        val ignoreIfBlockInteractor = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // We still want to allow deleting chats etc.,
                // so only ignore if BlockInteractor is calling
                val isBlockInteractor =
                    Thread.currentThread().stackTrace.any { stackTraceElement ->
                        GApp.manager.BlockInteractor.any {
                            stackTraceElement.className.contains(it)
                        } || stackTraceElement.className.contains(GApp.ui.chat.BlockViewModel)
                    }
                if (isBlockInteractor) {
                    param.result = null
                }
            }
        }

        findAndHookMethod(
            GApp.persistence.repository.ProfileRepo,
            Hooker.pkgParam.classLoader,
            GApp.persistence.repository.ProfileRepo_.delete,
            String::class.java,
            "kotlin.coroutines.Continuation",
            ignoreIfBlockInteractor
        )

        findAndHookMethod(
            GApp.persistence.repository.ProfileRepo,
            Hooker.pkgParam.classLoader,
            GApp.persistence.repository.ProfileRepo_.delete,
            List::class.java,
            "kotlin.coroutines.Continuation",
            ignoreIfBlockInteractor
        )

        findAndHookMethod(
            GApp.manager.persistence.ChatPersistenceManager,
            Hooker.pkgParam.classLoader,
            GApp.manager.persistence.ChatPersistenceManager_.deleteConversationsByProfileIds,
            List::class.java,
            "kotlin.coroutines.Continuation",
            ignoreIfBlockInteractor
        )

        // We just remove the "AND blocks.profileId is NULL" part to allow blocked profiles
        val queries = mapOf(
            "\n" +
                    "        SELECT * FROM conversation \n" +
                    "        LEFT JOIN blocks ON blocks.profileId = conversation_id\n" +
                    "        LEFT JOIN banned ON banned.profileId = conversation_id\n" +
                    "        WHERE blocks.profileId is NULL AND banned.profileId is NULL\n" +
                    "        ORDER BY conversation.pin DESC, conversation.last_message_timestamp DESC, conversation.conversation_id DESC\n" +
                    "        "
                    to "\n" +
                    "        SELECT * FROM conversation \n" +
                    "        LEFT JOIN blocks ON blocks.profileId = conversation_id\n" +
                    "        LEFT JOIN banned ON banned.profileId = conversation_id\n" +
                    "        WHERE banned.profileId is NULL\n" +
                    "        ORDER BY conversation.pin DESC, conversation.last_message_timestamp DESC, conversation.conversation_id DESC\n" +
                    "        ",
            "\n" +
                    "        SELECT * FROM conversation\n" +
                    "        LEFT JOIN profile ON profile.profile_id = conversation.conversation_id\n" +
                    "        LEFT JOIN blocks ON blocks.profileId = conversation_id\n" +
                    "        LEFT JOIN banned ON banned.profileId = conversation_id\n" +
                    "        WHERE blocks.profileId is NULL AND banned.profileId is NULL AND unread >= :minUnreadCount AND is_group_chat in (:isGroupChat)\n" +
                    "            AND (:minLastSeen = 0 OR seen > :minLastSeen)\n" +
                    "            AND (1 IN (:isFavorite) AND 0 IN (:isFavorite) OR is_favorite in (:isFavorite))\n" +
                    "        ORDER BY conversation.pin DESC, conversation.last_message_timestamp DESC, conversation.conversation_id DESC\n" +
                    "        "
                    to "\n" +
                    "        SELECT * FROM conversation\n" +
                    "        LEFT JOIN profile ON profile.profile_id = conversation.conversation_id\n" +
                    "        LEFT JOIN blocks ON blocks.profileId = conversation_id\n" +
                    "        LEFT JOIN banned ON banned.profileId = conversation_id\n" +
                    "        WHERE banned.profileId is NULL AND unread >= :minUnreadCount AND is_group_chat in (:isGroupChat)\n" +
                    "            AND (:minLastSeen = 0 OR seen > :minLastSeen)\n" +
                    "            AND (1 IN (:isFavorite) AND 0 IN (:isFavorite) OR is_favorite in (:isFavorite))\n" +
                    "        ORDER BY conversation.pin DESC, conversation.last_message_timestamp DESC, conversation.conversation_id DESC\n" +
                    "        "
        )

        findAndHookMethod("androidx.room.RoomSQLiteQuery",
            Hooker.pkgParam.classLoader,
            "acquire",
            String::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val query = param.args[0]
                    param.args[0] = queries.getOrDefault(query, query)
                }
            })
    }

    /**
     * Shows when a user has blocked you or unblocked you.
     * Shows when you block or unblock a user in chat.
     */
    fun showBlocksInChat() {
        val receiveChatMessage = findMethodExact(
            GApp.xmpp.ChatMessageManager,
            Hooker.pkgParam.classLoader,
            GApp.xmpp.ChatMessageManager_.handleIncomingChatMessage,
            findClass("com.grindrapp.android.persistence.model.ChatMessage", Hooker.pkgParam.classLoader),
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )

        XposedBridge.hookMethod(receiveChatMessage,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val syntheticMessage = when (getObjectField(
                        param.args[0], "type")) {
                        "block" -> "[You have been blocked]"
                        "unblock" -> "[You have been unblocked]"
                        else -> null
                    }

                    if (syntheticMessage != null) {
                        val clone = param.args[0].javaClass
                            .getMethod("clone").invoke(param.args[0])

                        setObjectField(clone, "body", syntheticMessage)
                        setObjectField(clone, "type", "text")

                        receiveChatMessage.invoke(
                            param.thisObject,
                            clone,
                            param.args[1],
                            param.args[2]
                        )
                    }
                }
            }
        )

        findAndHookMethod(
            GApp.storage.UserSession,
            Hooker.pkgParam.classLoader,
            GApp.storage.IUserSession_.getProfileId,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    ownProfileId = param.result as String
                }
            }
        )

        findAndHookMethod(
            GApp.persistence.repository.BlockRepo,
            Hooker.pkgParam.classLoader,
            GApp.persistence.repository.BlockRepo_.add,
            GApp.persistence.model.BlockedProfile,
            "kotlin.coroutines.Continuation",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val otherProfileId = callMethod(
                        param.args[0],
                        GApp.persistence.model.BlockedProfile_.getProfileId
                    ) as String
                    logChatMessage("[You have blocked this profile]", otherProfileId)
                }
            }
        )

        findAndHookMethod(
            GApp.persistence.repository.BlockRepo,
            Hooker.pkgParam.classLoader,
            GApp.persistence.repository.BlockRepo_.delete,
            String::class.java,
            "kotlin.coroutines.Continuation",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val otherProfileId = param.args[0] as? String
                    if (otherProfileId != null) {
                        logChatMessage("[You have unblocked this profile]", otherProfileId)
                    }
                }
            }
        )
    }

    /**
     * Creates a local terminal which can be used to execute commands
     * in any chat by using the '/' prefix.
     */
    fun createChatTerminal() {
        val sendChatMessage = findMethodExact("z5.b", Hooker.pkgParam.classLoader, "r",
            findClass("com.grindrapp.android.chat.model.ChatMessageMetaData", Hooker.pkgParam.classLoader)
        )

        hookMethod(sendChatMessage, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val chatMessage = getObjectField(param.args[0], "chatMessage")
                val content = getObjectField(chatMessage, "content")
                val sender = getObjectField(content, "sender") as String
                val recipient = getObjectField(content, "recipient") as String
                val body = getObjectField(content, "body") as String
                val text = JSONObject(body).getString("text")

                if (text.startsWith("/")) {
                    param.result = null // Prevents the command from being sent as a message
                    val commandHandler = CommandHandler(recipient, sender)
                    commandHandler.handleCommand(text.substring(1))
                }
            }
        })

    }

    /**
     * Disables the automatic deletion of messages.
     */
    fun disableAutomaticMessageDeletion() {
        findAndHookMethod(
            GApp.persistence.repository.ChatRepo,
            Hooker.pkgParam.classLoader,
            GApp.persistence.repository.ChatRepo_.deleteChatMessageFromLessThanOrEqualToTimestamp,
            Long::class.java,
            "kotlin.coroutines.Continuation",
            RETURN_UNIT
        )
    }

    fun dontSendChatMarkers() {
        findAndHookMethod(
            "org.jivesoftware.smack.AbstractXMPPConnection",
            Hooker.pkgParam.classLoader,
            "sendStanza",
            "org.jivesoftware.smack.packet.Stanza",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val stanza = param.args[0]
                    val hasReceivedExtension = callMethod(stanza, "hasExtension", "received", "urn:xmpp:chat-markers:0") as Boolean
                    val hasDisplayedExtension = callMethod(stanza, "hasExtension", "displayed", "urn:xmpp:chat-markers:0") as Boolean
                    if (hasReceivedExtension || hasDisplayedExtension) {
                        param.result = null
                    }
                }
            })
    }

    /**
     * Hook the method that sends the typing indicator to the server.
     */
    fun dontSendTypingIndicator() {
        findAndHookMethod(
            "org.jivesoftware.smackx.chatstates.ChatStateManager",
            Hooker.pkgParam.classLoader,
            "setCurrentState",
            "org.jivesoftware.smackx.chatstates.ChatState",
            "org.jivesoftware.smack.chat2.Chat",
            XC_MethodReplacement.DO_NOTHING
        )
    }

    /**
     * Makes messages always removable, even if they're older than
     * 24 hours.
     */
    fun makeMessagesAlwaysRemovable() {
        findAndHookMethod(
            "com.grindrapp.android.persistence.model.ChatMessageContent",
            Hooker.pkgParam.classLoader,
            "getTimestamp",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Thread.currentThread().stackTrace.any {
                            it.className.contains("ub.x2") }) {
                        // Only hook if the caller is showMessageLongClickDialog
                        param.result = System.currentTimeMillis()
                    }
                }
            }
        )
    }


    /**
     * Use a three column layout for the favorites tab.
     */
    fun useThreeColumnLayoutForFavorites() {
        val LayoutParamsRecyclerViewConstructor = findConstructorExact(
            "androidx.recyclerview.widget.RecyclerView\$LayoutParams",
            Hooker.pkgParam.classLoader,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        findAndHookMethod(
            GApp.favorites.FavoritesFragment,
            Hooker.pkgParam.classLoader,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.args[0] as View
                    val recyclerView = view.findViewById<View>(
                        Hooker.appContext.resources.getIdentifier(
                            "fragment_favorite_recycler_view",
                            "id",
                            Hooker.pkgParam.packageName
                        ))
                    val gridLayoutManager = callMethod(recyclerView, "getLayoutManager")
                    callMethod(gridLayoutManager, "setSpanCount", NUM_OF_COLUMNS)

                    val adapter = callMethod(recyclerView, "getAdapter")

                    findAndHookMethod(
                        adapter::class.java,
                        "onBindViewHolder",
                        "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                        Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                // Adjust the grid item size
                                val size = Hooker.appContext.resources
                                    .displayMetrics.widthPixels / NUM_OF_COLUMNS
                                val rootLayoutParams = LayoutParamsRecyclerViewConstructor
                                    .newInstance(size, size) as LayoutParams

                                val itemView = getObjectField(
                                    param.args[0], "itemView") as View
                                itemView.layoutParams = rootLayoutParams

                                val distanceTextView =
                                    itemView.findViewById<TextView>(
                                        Hooker.appContext.resources.getIdentifier(
                                            "profile_distance",
                                            "id",
                                            Hooker.pkgParam.packageName
                                        ))

                                // Make online status and distance appear below each other
                                // because there's not enough space anymore to show them in
                                // a single row.
                                val linearLayout = distanceTextView.parent as LinearLayout
                                linearLayout.orientation = LinearLayout.VERTICAL

                                // Adjust layout params because of different orientation of
                                // LinearLayout
                                linearLayout.children.forEach { child ->
                                    child.layoutParams = LinearLayout.LayoutParams(
                                        LayoutParams.MATCH_PARENT,
                                        LayoutParams.WRAP_CONTENT
                                    )
                                }

                                // Align distance TextView left now that it's displayed in
                                // its own row.
                                distanceTextView.gravity = Gravity.START

                                // Remove ugly margin before last seen text when online
                                // indicator is invisible.
                                val profileOnlineNowIcon =
                                    itemView.findViewById<ImageView>(
                                        Hooker.appContext.resources.getIdentifier(
                                            "profile_online_now_icon",
                                            "id",
                                            Hooker.pkgParam.packageName
                                        ))
                                val profileLastSeen =
                                    itemView.findViewById<TextView>(
                                        Hooker.appContext.resources.getIdentifier(
                                            "profile_last_seen",
                                            "id",
                                            Hooker.pkgParam.packageName
                                        ))
                                val lastSeenLayoutParams = profileLastSeen
                                    .layoutParams as LinearLayout.LayoutParams
                                if (profileOnlineNowIcon.visibility == View.GONE) {
                                    lastSeenLayoutParams.marginStart = 0
                                } else {
                                    lastSeenLayoutParams.marginStart = TypedValue.applyDimension(
                                        TypedValue.COMPLEX_UNIT_DIP,
                                        5f,
                                        profileLastSeen.resources.displayMetrics
                                    ).roundToInt()
                                }
                                profileLastSeen.layoutParams = lastSeenLayoutParams

                                // Remove ugly margin before display name when note icon is
                                // invisible.
                                val profileNoteIcon =
                                    itemView.findViewById<ImageView>(
                                        Hooker.appContext.resources.getIdentifier(
                                            "profile_note_icon",
                                            "id",
                                            Hooker.pkgParam.packageName
                                        ))
                                val profileDisplayName =
                                    itemView.findViewById<TextView>(
                                        Hooker.appContext.resources.getIdentifier(
                                            "profile_display_name",
                                            "id",
                                            Hooker.pkgParam.packageName
                                        ))
                                val displayNameLayoutParams = profileDisplayName
                                    .layoutParams as LinearLayout.LayoutParams
                                if (profileNoteIcon.visibility == View.GONE) {
                                    displayNameLayoutParams.marginStart = 0
                                } else {
                                    displayNameLayoutParams.marginStart = TypedValue.applyDimension(
                                        TypedValue.COMPLEX_UNIT_DIP,
                                        4f,
                                        profileLastSeen.resources.displayMetrics
                                    ).roundToInt()
                                }
                                profileDisplayName.layoutParams = displayNameLayoutParams
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * Disables all kind of analytics provided by Grindr.
     */
    fun disableAnalytics() {
        val AnalyticsRestServiceClass = findClass(
            GApp.api.AnalyticsRestService,
            Hooker.pkgParam.classLoader
        )

        val createSuccessResultConstructor = findConstructorExact(
            "c9.a\$b",
            Hooker.pkgParam.classLoader,
            Any::class.java
        )

        findAndHookMethod(
            "retrofit2.Retrofit",
            Hooker.pkgParam.classLoader,
            "create",
            Class::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.result
                    param.result = when {
                        AnalyticsRestServiceClass.isInstance(service) -> {
                            Proxy.newProxyInstance(
                                Hooker.pkgParam.classLoader,
                                arrayOf(AnalyticsRestServiceClass)
                            ) { proxy, method, args ->
                                // Just block all methods for now, in the future
                                // we might need to differentiate if they change
                                // the service interface.
                                createSuccessResultConstructor.newInstance(Unit)
                            }
                        }
                        else -> service
                    }
                }
            }
        )
    }

    /*
    DO NOT ENABLE THIS IN PRODUCTION BUILDS!
    This methods disables OkHttp's certificate pinning trusts all certificate.
    Useful for analyzing network traffic with tools like mitmproxy.
    THIS COMPLETELY MITIGATES ANY SECURITY AND SHOULD NOT BE USED IN PRODUCTION
     */
    fun trustAllCerts() {
        XposedHelpers.findAndHookConstructor(
            "okhttp3.OkHttpClient\$Builder",
            Hooker.pkgParam.classLoader,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val trustAlLCerts = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<out X509Certificate>?,
                            authType: String?
                        ) {
                        }

                        override fun checkServerTrusted(
                            chain: Array<out X509Certificate>?,
                            authType: String?
                        ) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                    })
                    val sslContext = SSLContext.getInstance("TLSv1.3")
                    sslContext.init(null, trustAlLCerts, SecureRandom())
                    callMethod(param.thisObject, "sslSocketFactory", sslContext.socketFactory, trustAlLCerts.first() as X509TrustManager)
                    callMethod(param.thisObject, "hostnameVerifier", object : HostnameVerifier {
                        override fun verify(hostname: String?, session: SSLSession?): Boolean = true
                    })
                }
            })

        findAndHookMethod(
            "okhttp3.OkHttpClient\$Builder",
            Hooker.pkgParam.classLoader,
            "certificatePinner",
            "okhttp3.CertificatePinner",
            XC_MethodReplacement.DO_NOTHING)
    }

    fun modifyProfileDetails() {
        if (Hooker.config.readBoolean("show_profile_details", true)) {
            findAndHookMethod(
                "com.grindrapp.android.ui.profileV2.ProfileExpandedDetailsView",
                Hooker.pkgParam.classLoader,
                "a", // setProfile
                findClass("com.grindrapp.android.ui.profileV2.model.ProfileViewState", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.analytics.GrindrAnalyticsV2", Hooker.pkgParam.classLoader),
                findClass("com.grindrapp.android.base.model.profile.ReferrerType", Hooker.pkgParam.classLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val profile = getObjectField(param.thisObject, "c") ?: return
                        val rootView = getObjectField(profile, "a") as? ViewGroup ?: return
                        val statsView = rootView.getChildAt(3) as? ViewGroup ?: return

                        findHeightAndWeightTextViews(statsView)?.let { (height, weight) ->
                            if (height != null && weight != null) {
                                val bmi = Utils.getBMIDescription(height.text.toString(), weight.text.toString())
                                weight.text = "${weight.text} - BMI $bmi"
                            }
                        }
                    }
                }
            )
        }
    }

    fun useMorePreciseDistanceDisplay() {
        findAndHookMethod(
            "com.grindrapp.android.utils.DistanceUtils",
            Hooker.pkgParam.classLoader,
            "b",
            Boolean::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    val distance = param.args[1] as Double
                    val isFeet = param.args[2] as Boolean

                    return if(isFeet){
                        val feet = (distance * 3.280839895).roundToInt()
                        if(feet < 5280) {
                            String.format("%d feet", feet)
                        } else {
                            String.format("%d miles %d feet", feet / 5280, feet % 5280)
                        }
                    } else {
                        val meters = distance.roundToInt()
                        if(meters < 1000) {
                            String.format("%d meters", meters)
                        } else {
                            String.format("%d km %d m", meters / 1000, meters % 1000)
                        }
                    }
                }
            }
        )
    }
}
