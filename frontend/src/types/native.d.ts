export {};

declare global {
  interface BrachaNativeBridge {
    setAuth(token: string, refreshToken: string): void;
    clearAuth(): void;
    /**
     * Device-local storage settings. Optional because the committed web bundle can run
     * inside an older APK whose bridge predates them.
     */
    getDeleteAudioAfterProcessing?(): boolean;
    setDeleteAudioAfterProcessing?(enabled: boolean): void;
  }

  interface Window {
    /** Injected by the Android host. Undefined in a plain browser. */
    BrachaNative?: BrachaNativeBridge;
  }
}
