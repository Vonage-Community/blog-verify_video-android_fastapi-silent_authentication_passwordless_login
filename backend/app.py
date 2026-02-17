from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import os
from dotenv import load_dotenv

from vonage import Auth, Vonage
from vonage_verify import SilentAuthChannel, VerifyRequest, SmsChannel
from vonage_video.models import TokenOptions
from vonage_http_client import HttpRequestError

load_dotenv()
app = FastAPI()

client = Vonage(
    Auth(
        application_id=os.environ["VONAGE_APPLICATION_ID"],
        private_key=os.environ["VONAGE_PRIVATE_KEY_PATH"],
    )
)


class StartVerifyIn(BaseModel):
    phone_number: str


@app.post("/verify/start")
def verify_start(body: StartVerifyIn):
    silent_req = VerifyRequest(
        brand="DemoApp",
        workflow=[SilentAuthChannel(to=body.phone_number)],
        coverage_check=True
    )

    try:
        verify_resp = client.verify.start_verification(silent_req)
        return {
            "request_id": verify_resp.request_id,
            "check_url": getattr(verify_resp, "check_url", None),
            "channel": "silent_auth",
        }

    except HttpRequestError as e:
        error_resp = getattr(e, "response", None)
        if error_resp is None:
            raise HTTPException(
                status_code=424,
                detail={
                    "error": "verify_unreachable",
                    "message": str(e),
                    "retryable": True,
                }
            )

        payload = {}
        try:
            data = error_resp.json()
            payload = data if isinstance(data, dict) else {"detail": str(data)}
        except Exception:
            payload = {"detail": str(e)}

        if error_resp.status_code == 412:
            sms_req = VerifyRequest(
                brand="DemoApp",
                workflow=[SmsChannel(to=body.phone_number)],
            )
            try:
                verify_resp2 = client.verify.start_verification(sms_req)
                return {
                    "request_id": verify_resp2.request_id,
                    "channel": "sms",
                }
            except HttpRequestError as e2:
                error_resp2 = getattr(e2, "response", None)
                if error_resp2 is None:
                    raise HTTPException(
                        status_code=424,
                        detail={
                            "error": "verify_unreachable",
                            "message": str(e2),
                            "retryable": True,
                        }
                    )
                payload2 = {}
                try:
                    data2 = error_resp2.json()
                    payload2 = data2 if isinstance(data2, dict) else {
                        "detail": str(data2)}
                except Exception:
                    payload2 = {"detail": str(e2)}
                raise HTTPException(
                    status_code=error_resp2.status_code, detail=payload2)

        raise HTTPException(status_code=error_resp.status_code, detail=payload)


class ConfirmVerifyIn(BaseModel):
    request_id: str
    code: str


@app.post("/verify/confirm")
def verify_confirm_silent(body: ConfirmVerifyIn):
    try:
        verify_resp3 = client.verify.check_code(body.request_id, body.code)
        status = verify_resp3 if isinstance(verify_resp3, str) else getattr(
            verify_resp3, "status", str(verify_resp3))
        return {"status": status}
    except HttpRequestError as e3:
        error_resp3 = getattr(e3, "response", None)
        if error_resp3 is None:
            raise HTTPException(
                status_code=424,
                detail={
                    "error": "verify_unreachable",
                    "message": str(e3),
                    "retryable": True,
                }
            )
        payload = {"detail": str(e3)}
        try:
            data = error_resp3.json()
            if isinstance(data, dict):
                payload = data
        except Exception:
            pass
        raise HTTPException(
            status_code=error_resp3.status_code,
            detail=payload
        )


@app.post("/video/token")
def video_token():
    token_options = TokenOptions(
        session_id=os.environ["VONAGE_VIDEO_SESSION_ID"], role="publisher"
    )
    token = client.video.generate_client_token(token_options)
    return {
        "apiKey": os.environ["VONAGE_APPLICATION_ID"],
        "sessionId": os.environ["VONAGE_VIDEO_SESSION_ID"],
        "token": token,
    }
