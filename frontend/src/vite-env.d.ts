/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BACKEND?: 'server' | 'sim'
  readonly VITE_OPERATOR_NAME?: string
  readonly VITE_OPERATOR_EMAIL?: string
}
