package com.arabic

import android.util.Base64
import com.lagradost.cloudstream3.app


object ReCaptcha {

    suspend fun getCaptchaToken(
        url: String,
        siteKey: String,
    ): String? {
        // logic based on: https://github.com/google/recaptcha/issues/293#issuecomment-746867623
        // and the tutorial provided.

        // 1. Construct 'co' (cookie/origin?) parameter
        // The tutorial says: simple base64 of "$url:443" and replacing = with .
        // URL should be the origin, e.g. "https://site.com"
        // Ensure strictly no trailing slash for the base64 part if adhering to the example
        val cleanUrl = url.trimEnd('/')
        val coPayload = "$cleanUrl:443"
        val co = Base64.encodeToString(coPayload.toByteArray(), Base64.NO_WRAP).replace("=", ".")

        // 2. Get vtoken (version token)
        // url: https://www.google.com/recaptcha/api.js?render=$key
        val apiJsUrl = "https://www.google.com/recaptcha/api.js?render=$siteKey"
        val apiJsDoc = app.get(apiJsUrl).text
        // Regex to find: po.src=...releases/(.*)/recaptcha...
        val version = Regex("releases/([^/]+)/recaptcha").find(apiJsDoc)?.groupValues?.get(1) 
            ?: return null

        // 3. Get Recaptcha Token
        // url: https://www.google.com/recaptcha/api2/anchor?ar=1&hl=en&size=invisible&cb=cs3&k=${key}&co=${co}&v=${vtoken}
        val anchorUrl = "https://www.google.com/recaptcha/api2/anchor?ar=1&hl=en&size=invisible&cb=cs3&k=$siteKey&co=$co&v=$version"
        val anchorDoc = app.get(anchorUrl).text
        
        // Regex to find: id="recaptcha-token" value="([^"]*)"
        val token = Regex("id=\"recaptcha-token\" value=\"([^\"]+)\"").find(anchorDoc)?.groupValues?.get(1)
        
        return token
    }
}
