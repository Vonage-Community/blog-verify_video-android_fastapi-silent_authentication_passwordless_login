## Table of Contents  
- [Overview](#overview)
- [Demo Flow](#demo-flow)
- [Architecture](#Architecture)
- [Prerequisites](#Prerequisites)
  - [Notes](#notes)
- [Setup & Run for Development](#setup--run-for-development)
  - [Application Setup](#application-setup)
  - [Backend Setup](#backend-setup)
  - [Android Setup](#android-setup)
- [How to Use the App](#how-to-use-the-app)
- [Error Handling](#error-handling)
- [Failure Scenarios](#failure-scenarios)
- [Production Recommendations](#production-recommendations)

# Overview
This repository demonstrates how [Vonage Network APIs](https://developer.vonage.com/en/getting-started-network/concepts/network-features) and [Vonage Video API](https://developer.vonage.com/en/video/overview?source=video) can be combined to create a **network-powered, passwordless** onboarding experience for real-time video applications.  

Using [Silent Authentication (Silent Auth)](https://developer.vonage.com/en/verify/concepts/silent-authentication) via the [Verify API](https://developer.vonage.com/en/verify/overview) with application-level [SMS](https://developer.vonage.com/en/verify/concepts/workflow?source=verify#sms) fallback, the app verifies device ownership before generating a Video API token and joining a live session.  

- Vonage APIs  
  - **Verify API**: Device ownership validation using Silent Auth / SMS fallback    
  - **Video API**: Real-time video creation and access  

For the architectural decisions and product rationale behind this demo, see the accompanying blog post: https://developer.vonage.com/en/blog/build-passwordless-video-login-with-silent-auth-and-the-video-api  

# Demo flow
Watch the end-to-end flow (Silent Auth -> Video session): https://www.youtube.com/watch?v=6K9OyGoh5Cw

# Architecture  
The solution is intentionally designed with a clear separation of concerns. Authentication and video are deliberately decoupled. Verification completes first, only then is a Video token issued and the session initialized.  
- **Android Client**
  - Collects the phone number
  - Routes based on verification channel (silent_auth or sms)
  - Forces the Silent Auth request over the mobile network using the [library](https://developer.vonage.com/en/verify/concepts/silent-authentication?source=verify#bypass-wifi-for-silent-authentication)
  - Joins the Video session only after verification is completed
- **Python Backend (FastAPI)**
  - Initiates verification via the Verify API
  - Treats Silent Auth support as a routing decision
  - Confirms verification
  - Generates a Video API token

# Prerequisites  
To run this demo, you will need:
- [A Vonage API account](https://developer.vonage.com/sign-up)
- Python<=3.9
- Android Studio
- A physical device with mobile data enabled SIM

## Notes  
- Video can also work with an **OpenTok** account, but Verify requires a **Vonage API account**.
- The Android **emulator** can be used for basic UI testing using a [Virtual Operator](https://developer.vonage.com/en/verify/concepts/virtual-operator-silent-auth?source=verify). 
- To test Silent Auth, you have two options:
  - Use the Virtual Operator provided for testing purposes **without a physical device**  
  - Use a **SIM** from a [supported mobile operator](https://developer.vonage.com/en/verify/concepts/silent-authentication?source=verify#availability) with mobile data enabled on a **physical device**
- For testing, a supported phone number must be registered in the [Network Registry Playground](https://developer.vonage.com/en/getting-started-network/concepts/playground).
- In a production environment, registration with the [Network Registry](https://developer.vonage.com/en/verify/concepts/silent-authentication?source=verify#production) is required.

# Setup & Run for Development
## Application Setup
Before running the demo, make sure your Vonage application is properly configured:
  1. Log in to the [Vonage API Dashboard](https://dashboard.vonage.com/applications).
  2. Create or open an existing Application.
  3. Enable the following capabilities:  
    - Video  
    - Network Registry with **Playground** access type
  4. Select **Generate public and private key** > **Generate new application**
  5. Open **Configure Playground** > **Add numbers** and add a phone number from a supported operator.  

## Backend Setup
  1. Run the following commands under the `backend` folder:  
```
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```
  2. Rename the `.env.example` to `.env` and add your credentials:
```
VONAGE_APPLICATION_ID=application_id
VONAGE_PRIVATE_KEY_PATH=path_to_private.key
VONAGE_VIDEO_SESSION_ID=video_session_id
```
For test purposes, a fixed Video Session ID can be generated via the [Video Playground](https://tools.vonage.com/video/playground).  

  3. Run the backend:
```
uvicorn app:app --reload --host 0.0.0.0 --port 8000
```
If testing on a physical device within the same network, obtain your machine’s local IP address (e.g. `ipconfig getifaddr en0` on macOS) and update the base URL accordingly (e.g. http://192.168.1.1:8000). Alternatively, expose the backend using `ngrok`.  

## Android Setup
  1. Open the `client/android` project in Android Studio
  2. Update the backend base URL in `ServerConfig.kt`
  3. Run on a physical device

# How to Use the App
1. Enter a phone number in E.164 format (e.g. 447XXXXXXXXX)
2. Tab **Verify and Join**

Then the flow proceeds based on network support:
- Supported number: Silent Auth is triggered over the mobile network.
- Virtual Operator with even digit: Silent Auth is simulated and completes without a physical SIM.
- Unsupported number: The app falls back to SMS verification.  

# Error Handling
This demo is designed to **fail safely** without crashing, and to keep users moving through the flow with clear recovery paths. Error handling aligns with the [Verify API response model](https://developer.vonage.com/en/api/verify.v2).  
- **No app-killing failures for recoverable errors**  
Network issues, unsupported carriers, invalid inputs, and unexpected responses are handled in-place with user-facing guidance.  
- **UI always returns to an actionable state**  
Every failure path unlocks the relevant UI (buttons re-enabled) and returns focus to the next expected action.  
- **Errors are routed, not treated as exceptions**  
Silent Auth “not supported” is handled as a routing outcome (SMS fallback), not a hard failure.  

# Failure Scenarios
- **Input validation**  
Invalid phone numbers / SMS codes are blocked client-side with field-level errors and focus.  
- **Silent Auth check failures**  
If the cellular request fails (or returns an error payload), the app does not close—it unlocks the UI and prompts the user to retry (or proceed via SMS when applicable).  
- **Verification confirmation failures**  
Non-200 responses or non-completed statuses keep the user in the authentication flow, with a retry path.  
- **Video token failures**  
Token fetch errors are treated as recoverable network/setup issues. The UI is unlocked and the user is prompted to try again (video is not started in a partial state).

# Production Recommendations
- **Create sessions dynamically** (per user, per room, or per meeting) and issue short-lived tokens scoped to that session.  
- Replace generic error message like “Silent Auth failed” with **action-based and user-friendly** messages (“Out of range, check your mobile network and try again”, “It looks like you have multiple SIMs. Make sure mobile data is enabled for this number and try again.”).  
- **Add retry limits + backoff** for transient network errors.  
- **Log full technical details to telemetry**, but show minimal user text.  
- Provide a single **“Start over” CTA** that resets state (verifyRequestId, verifyCheckUrl, UI mode, locks).  









