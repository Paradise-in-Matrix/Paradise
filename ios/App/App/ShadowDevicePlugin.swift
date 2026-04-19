import Foundation
import Capacitor
import MatrixRustSDK

@objc(ShadowDevicePlugin)
public class ShadowDevicePlugin: CAPPlugin, CAPBridgedPlugin {
    
    public let identifier = "ShadowDevicePlugin"
    public let jsName = "ShadowDevice"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "createSleepyShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "activateShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deactivateShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifyShadow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getShadowStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getActivePushUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPreviewPreferences", returnType: CAPPluginReturnPromise)
    ]

    let appGroupId = "group.com.gigiaj.paradise"
    
    private func buildMatrixClient(homeserverUrl: String, ssStr: String) async throws -> Client {
        guard let containerUrl = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else {
            throw NSError(domain: "ShadowDevice", code: 1, userInfo: [NSLocalizedDescriptionKey: "App Group container not found!"])
        }
        
        let dataPath = containerUrl.appendingPathComponent("matrix-shadow/data").path
        let cachePath = containerUrl.appendingPathComponent("matrix-shadow/cache").path
        
        let ssBuilder: SlidingSyncVersionBuilder
        switch ssStr {
        case "NATIVE": ssBuilder = .native
        case "NONE": ssBuilder = .none
        default: ssBuilder = .discoverNative
        }
        
        return try await ClientBuilder()
            .homeserverUrl(url: homeserverUrl)
            .sessionPaths(dataPath: dataPath, cachePath: cachePath)
            .systemIsMemoryConstrained()
            .slidingSyncVersionBuilder(versionBuilder: ssBuilder)
            .build()
    }
    
    private func restoreShadowSession(client: Client, sharedDefaults: UserDefaults) async throws {
        guard let accessToken = sharedDefaults.string(forKey: "access_token"),
              let userId = sharedDefaults.string(forKey: "user_id"),
              let deviceId = sharedDefaults.string(forKey: "device_id"),
              let homeserverUrl = sharedDefaults.string(forKey: "homeserver_url") else {
            throw NSError(domain: "ShadowDevice", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing session in UserDefaults"])
        }
        
        let slidingSyncStr = sharedDefaults.string(forKey: "sliding_sync_version") ?? "NONE"
        let ssVersion: SlidingSyncVersion = (slidingSyncStr == "NATIVE") ? .native : .none
        
        let session = Session(
            accessToken: accessToken,
            refreshToken: nil,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl,
            oidcData: nil,
            slidingSyncVersion: ssVersion
        )
        
        try await client.restoreSession(session: session)
    }


@objc func createSleepyShadow(_ call: CAPPluginCall) {
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!
        
        if call.getString("password") == nil {
            if sharedDefaults.string(forKey: "access_token") != nil {
                call.resolve(["status": "sleepy_shadow_already_exists"])
                return
            } else {
                call.reject("Missing password and no existing shadow session.")
                return
            }
        }
        
        guard let homeserver = call.getString("homeserverUrl"),
              let username = call.getString("username"),
              let password = call.getString("password") else {
            return call.reject("Missing arguments")
        }
        
        sharedDefaults.set(homeserver, forKey: "homeserver_url")
        
        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: "DISCOVER")
                try await client.login(username: username, password: password, initialDeviceName: "Paradise Background Sync", deviceId: nil)
                
                let session = try client.session()
                sharedDefaults.set(session.accessToken, forKey: "access_token")
                sharedDefaults.set(session.userId, forKey: "user_id")
                sharedDefaults.set(session.deviceId, forKey: "device_id")
                
                let ssName = (session.slidingSyncVersion == .native) ? "NATIVE" : "NONE"
                sharedDefaults.set(ssName, forKey: "sliding_sync_version")
                
                call.resolve(["status": "sleepy_shadow_created"])
            } catch {
                call.reject("Failed to create Sleepy Shadow", nil, error)
            }
        }
    }



    @objc func activateShadow(_ call: CAPPluginCall) {
        guard let pushToken = call.getString("pushToken"),
              let pushUrl = call.getString("pushUrl") else {
            return call.reject("Missing arguments")
        }
        
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!
        guard let homeserver = sharedDefaults.string(forKey: "homeserver_url") else {
            return call.reject("No homeserver saved")
        }
        let ssStr = sharedDefaults.string(forKey: "sliding_sync_version") ?? "NONE"


        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: ssStr)
                try await restoreShadowSession(client: client, sharedDefaults: sharedDefaults)
                let payloadDict: [String: Any] = [
                    "aps": [
                        "mutable-content": 1,
                        "alert": [
                            "loc-key": "Notification",
                            "loc-args": []
                        ]
                    ]
                ]
                
                let payloadData = try JSONSerialization.data(withJSONObject: payloadDict)
                guard let payloadString = String(data: payloadData, encoding: .utf8) else {
                    return call.reject("Failed to encode payload string")
                }
                
                let identifiers = PusherIdentifiers(pushkey: pushToken, appId: "moi.paradise.ios")
                let httpData = HttpPusherData(url: pushUrl, format: .eventIdOnly, defaultPayload: payloadString)


                try await client.setPusher(
                    identifiers: identifiers,
                    kind: PusherKind.http(data: httpData),
                    appDisplayName: "Paradise",
                    deviceDisplayName: "iOS Device",
                    profileTag: "primary",
                    lang: "en"
                )
                
                call.resolve(["status": "shadow_activated"])
            } catch {
                call.reject("Failed to activate Shadow Device", nil, error)
            }
        }
    }
    
    
    @objc func deactivateShadow(_ call: CAPPluginCall) {
        guard let pushToken = call.getString("pushToken") else { return call.reject("Missing token") }
        
        let sharedDefaults = UserDefaults(suiteName: appGroupId)!
        guard let homeserver = sharedDefaults.string(forKey: "homeserver_url") else {
            return call.reject("No homeserver saved")
        }
        let ssStr = sharedDefaults.string(forKey: "sliding_sync_version") ?? "NONE"
        
        Task {
            do {
                let client = try await buildMatrixClient(homeserverUrl: homeserver, ssStr: ssStr)
                try await restoreShadowSession(client: client, sharedDefaults: sharedDefaults)
                
                let identifiers = PusherIdentifiers(pushkey: pushToken, appId: "moi.paradise.ios")
                try await client.deletePusher(identifiers: identifiers)
                
                call.resolve(["status": "shadow_deactivated"])
            } catch {
                call.reject("Failed to deactivate Shadow Device", nil, error)
            }
        }
    }
}

