package com.stockalert

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches real-time stock prices from Yahoo Finance v8 Chart API.
 * The v8 chart endpoint does NOT require a crumb or cookie — much more reliable.
 *
 * Each symbol is fetched individually:
 *   https://query1.finance.yahoo.com/v8/finance/chart/NIFTYBEES.NS?interval=1m&range=1d
 *
 * NSE symbols use ".NS" suffix:  NIFTYBEES → NIFTYBEES.NS
 */
object StockFetcher {

    data class StockPrice(
        val symbol: String,       // as stored by user e.g. "NIFTYBEES.NS"
        val price: Double,
        val prevClose: Double,
        val change: Double,
        val changePct: Double,
        val currency: String = "INR"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches prices for all symbols. Returns map of
     * ORIGINAL symbol string (as passed in) → StockPrice? (null on failure).
     * BLOCKING — call from a background thread.
     */
    fun fetchPrices(symbols: List<String>): Map<String, StockPrice?> {
        val result = mutableMapOf<String, StockPrice?>()
        for (sym in symbols) {
            result[sym] = fetchSingle(sym)
        }
        return result
    }

    private fun fetchSingle(symbol: String): StockPrice? {
        val encoded = symbol.trim().uppercase()
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1m&range=1d"

        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/112 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null

            val json  = JSONObject(body)
            val chart = json.getJSONObject("chart")

            // Check for API-level error
            if (!chart.isNull("error") && chart.get("error") != JSONObject.NULL) return null

            val resultArr = chart.getJSONArray("result")
            if (resultArr.length() == 0) return null

            val meta      = resultArr.getJSONObject(0).getJSONObject("meta")
            val price     = meta.optDouble("regularMarketPrice", Double.NaN)
            val prevClose = meta.optDouble("chartPreviousClose", Double.NaN)
            val currency  = meta.optString("currency", "INR")

            if (price.isNaN()) return null

            val safePrev  = if (prevClose.isNaN()) price else prevClose
            val change    = price - safePrev
            val changePct = if (safePrev != 0.0) (change / safePrev) * 100.0 else 0.0

            StockPrice(
                symbol    = symbol,
                price     = price,
                prevClose = safePrev,
                change    = change,
                changePct = changePct,
                currency  = currency
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Human-readable display name: strip .NS / .BO suffix */
    fun displayName(symbol: String): String =
        symbol.trim().uppercase()
            .removeSuffix(".NS")
            .removeSuffix(".BO")

    /** One line per stock for the notification body */
    fun formatLine(symbol: String, price: StockPrice?): String {
        val name = displayName(symbol)
        return if (price == null) {
            "• $name  —  unavailable"
        } else {
            val arrow  = if (price.change >= 0) "▲" else "▼"
            val sign   = if (price.change >= 0) "+" else ""
            "• $name  ₹${"%.2f".format(price.price)}  $arrow $sign${"%.2f".format(price.changePct)}%"
        }
    }
}
