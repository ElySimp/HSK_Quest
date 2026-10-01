"""
Auth schemas — request/response models for registration and login.
"""
from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Request schemas
# ---------------------------------------------------------------------------
class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=50, pattern=r"^[a-zA-Z0-9_]+$")
    password: str = Field(..., min_length=6, max_length=128)
    hsk_target: int = Field(default=1, ge=1, le=9)


class LoginRequest(BaseModel):
    username: str
    password: str


# ---------------------------------------------------------------------------
# Response schemas
# ---------------------------------------------------------------------------
class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


class UserResponse(BaseModel):
    id: int
    username: str
    hsk_target: int

    model_config = {"from_attributes": True}


class RegisterResponse(BaseModel):
    user: UserResponse
    access_token: str
    token_type: str = "bearer"
    message: str = "Registration successful! Your adventure begins."
