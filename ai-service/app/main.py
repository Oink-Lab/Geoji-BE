from fastapi import FastAPI

app = FastAPI(title="Geoji AI Service")


@app.get("/health")
def health():
    return {"status": "UP"}
