from fastapi import APIRouter
from datetime import datetime
from app.models.schemas import HealthResponse
from app.config import settings

router = APIRouter()


@router.get("/health", response_model=HealthResponse, summary="Check API Health")
async def health_check():
    return HealthResponse(
        status="healthy",
        service="PCScan Backend API",
        version="1.0.0",
        timestamp=datetime.utcnow().isoformat() + "Z"
    )


@router.get("/health/config", summary="Check configuration (non-sensitive)")
async def health_config():
    return {
        "status": "ok",
        "environment": settings.ENVIRONMENT,
        "host": settings.HOST,
        "port": settings.PORT,
        "gemini_model": settings.GEMINI_MODEL,
        "openai_model": settings.OPENAI_MODEL,
        "google_api_configured": len(settings.GOOGLE_API_KEY) > 0,
        "openai_api_configured": len(settings.OPENAI_API_KEY) > 0,
        "cors_origins": settings.CORS_ORIGINS,
        "max_upload_mb": settings.MAX_UPLOAD_SIZE_MB
    }
