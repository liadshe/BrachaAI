export {};

declare global {
  interface BrachaNativeBridge {
    setAuth(token: string, refreshToken: string): void;
    clearAuth(): void;
    /**
     * Durable mirror of the *web* session, kept separate from native's own token pair
     * (which rotates independently). Needed because file:// localStorage does not survive
     * the app process being killed. Optional for the same reason as the settings methods:
     * the committed bundle can run inside an older APK whose bridge predates them.
     */
    setWebSession?(token: string, refreshToken: string, user: string): void;
    getWebSession?(): string;
    clearWebSession?(): void;
    /**
     * Device-local storage settings. Optional because the committed web bundle can run
     * inside an older APK whose bridge predates them.
     */
    getDeleteAudioAfterProcessing?(): boolean;
    setDeleteAudioAfterProcessing?(enabled: boolean): void;
    /**
     * Opens the phone's own call-recording setting. The app never records calls itself, so
     * this is the only switch that does anything. Optional for the same reason as the
     * settings methods: the committed bundle can run inside an older APK.
     */
    openCallRecordingSettings?(): void;
  }

  interface Window {
    /** Injected by the Android host. Undefined in a plain browser. */
    BrachaNative?: BrachaNativeBridge;
  }
}
