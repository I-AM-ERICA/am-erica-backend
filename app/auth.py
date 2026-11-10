from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.schemas import Principal

bearer = HTTPBearer(auto_error=False)


@dataclass
class Identity:
    user: Principal
    token: str = ''


async def current_identity(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)) -> Identity:
    if request.app.state.settings.auth_mode == 'local':
        return Identity(Principal(id='local'))
    if credentials is None:
        raise HTTPException(401, '로그인이 필요합니다.', headers={'WWW-Authenticate': 'Bearer'})
    user = await request.app.state.supabase.verify_user(credentials.credentials)
    return Identity(user, credentials.credentials)
