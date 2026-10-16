// Compatibility shim: the REST plumbing now lives in ./lib/api-client.mjs. P12 deletes this file
// and repoints the remaining callers.
export { isLoopbackApiUrl, apiBaseUrl, authHeader, fetchWithTimeout } from './lib/api-client.mjs';
