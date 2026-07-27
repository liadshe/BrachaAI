export {};

declare global {
  interface BrachaNativeBridge {
    setAuth(token: string): void;
    clearAuth(): void;
  }

  interface Window {
    /** Injected by the Android host. Undefined in a plain browser. */
    BrachaNative?: BrachaNativeBridge;
  }
}
