FROM golang:1.21-bookworm AS builder
WORKDIR /app
COPY go.* ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -v -o server

FROM gcr.io/distroless/static-debian12
WORKDIR /app
COPY --from=builder /app/server /app/server
EXPOSE 8900
USER nonroot:nonroot
ENTRYPOINT ["/app/server"]
