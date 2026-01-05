package com.arabic

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ScraperInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        // Basic "Recursive Hell" / Retry Logic from tutorial
        // If we get a 403 or 503 (often Cloudflare/Protection), we can try to "bypass" or just retry.
        // Since we don't have a real magic Cloudflare solver, we will implement a simple retry strategy
        // that could be expanded with token injection later.
        
        if (response.code == 403 || response.code == 503) {
            response.close() // Close the failed response
            
            // Hypothetical "Magic" Bypass would go here
            // For now, simpler logic: maybe we just need to wait a bit or rotate generic headers?
            // In a real scenario, this is where you'd call: solveCloudflare(request.url)
            
            // Let's retry ONCE with a modified header to simulate "changing identity" slightly
            // or just a plain retry if it was a flake.
            val newRequest = request.newBuilder()
                .header("Cache-Control", "no-cache") 
                .build()
                
            try {
                // Thread.sleep(1000) // Don't block main thread in production without care, but ok for logic demo
                response = chain.proceed(newRequest)
            } catch (e: IOException) {
                // If retry fails, we just return the last error or throw
                throw e
            }
        }
        
        // Check for Recaptcha presence in body if 200 OK (soft block)
        // This requires buffering the body which is expensive, so use with caution.
        // val bodyString = response.peekBody(Long.MAX_VALUE).string()
        // if (bodyString.contains("recaptcha")) { ... }

        return response
    }
}
