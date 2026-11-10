import asyncio
from collections import OrderedDict
from contextlib import suppress

from fastapi import HTTPException

from app.auth import Identity
from app.schemas import AssistantId, AssistantRequest, Job


class LocalJobQueue:
    """개발용 단일 프로세스 큐. 재시작 시 사라지며 분산 워커용 큐가 아닙니다."""

    def __init__(self, execute, capacity: int = 100):
        self.execute = execute
        self.capacity = capacity
        self.queue = asyncio.Queue(maxsize=capacity)
        self.records = OrderedDict()
        self.task = None

    def start(self):
        self.task = asyncio.create_task(self.worker(), name='assistant-worker')

    async def stop(self):
        if self.task:
            self.task.cancel()
            with suppress(asyncio.CancelledError):
                await self.task
        # 대기 중 입력과 사용자 토큰을 종료 시 즉시 해제합니다.
        while not self.queue.empty():
            self.queue.get_nowait()
            self.queue.task_done()

    def submit(self, assistant: AssistantId, payload: AssistantRequest, identity: Identity) -> Job:
        if len(self.records) >= self.capacity:
            finished = next((key for key, (_, job) in self.records.items() if job.status in ('completed', 'failed')), None)
            if finished is None:
                raise HTTPException(429, '대기 중인 작업이 많습니다. 잠시 후 시도해 주세요.')
            del self.records[finished]
        job = Job(assistant=assistant)
        self.records[job.id] = (identity.user.id, job)
        self.queue.put_nowait((job, payload, identity))
        return job

    def get(self, job_id: str, identity: Identity) -> Job:
        record = self.records.get(job_id)
        if not record or record[0] != identity.user.id:
            raise HTTPException(404, '작업을 찾을 수 없습니다.')
        return record[1]

    async def worker(self):
        while True:
            job, payload, identity = await self.queue.get()
            try:
                job.status = 'running'
                job.result = await self.execute(job.assistant, payload, identity)
                job.status = 'completed'
            except HTTPException as exc:
                job.status, job.error = 'failed', str(exc.detail)
            except Exception:
                job.status, job.error = 'failed', '작업을 처리하지 못했습니다.'
            finally:
                self.queue.task_done()
                # 워커가 다음 요청을 기다리는 동안 입력·토큰을 유지하지 않습니다.
                del payload, identity
