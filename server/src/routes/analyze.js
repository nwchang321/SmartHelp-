const express = require('express');
const router = express.Router();
const { analyzeScreenshot, testConnection } = require('../services/geminiService');

// Rate limiting - simple in-memory implementation
const requestTimestamps = new Map();
const RATE_LIMIT_MS = 2000; // 2 seconds between requests per client

function checkRateLimit(clientId) {
    const now = Date.now();
    const lastRequest = requestTimestamps.get(clientId) || 0;

    if (now - lastRequest < RATE_LIMIT_MS) {
        return false;
    }

    requestTimestamps.set(clientId, now);
    return true;
}

// POST /api/analyze - Analyze screenshot and return guidance
router.post('/analyze', async (req, res) => {
    try {
        const { screenshot, userQuery } = req.body;
        const clientId = req.ip || 'unknown';

        // Validate input
        if (!screenshot) {
            return res.status(400).json({
                success: false,
                error: 'Missing screenshot',
                message: 'Please provide a base64 encoded screenshot'
            });
        }

        // Check rate limit
        if (!checkRateLimit(clientId)) {
            return res.status(429).json({
                success: false,
                error: 'Too many requests',
                message: 'Please wait a moment before sending another request'
            });
        }

        console.log(`[${new Date().toISOString()}] Analyzing screenshot for client: ${clientId}`);

        // Analyze with Gemini
        const analysis = await analyzeScreenshot(screenshot, userQuery);

        res.json({
            success: true,
            timestamp: new Date().toISOString(),
            ...analysis
        });

    } catch (error) {
        console.error('Analysis error:', error);
        res.status(500).json({
            success: false,
            error: 'Analysis failed',
            message: error.message
        });
    }
});

// GET /api/test - Test Gemini connection
router.get('/test', async (req, res) => {
    try {
        const result = await testConnection();
        res.json({
            success: true,
            message: 'Gemini API connection successful',
            response: result
        });
    } catch (error) {
        res.status(500).json({
            success: false,
            error: 'Connection test failed',
            message: error.message
        });
    }
});

// GET /api/status - Server status
router.get('/status', (req, res) => {
    res.json({
        success: true,
        server: 'SmartHelp+ Backend',
        version: '1.0.0',
        endpoints: {
            analyze: 'POST /api/analyze',
            test: 'GET /api/test',
            status: 'GET /api/status'
        }
    });
});

module.exports = router;
