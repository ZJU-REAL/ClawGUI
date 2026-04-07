"""
Memory Store - Core storage system for personalized memories.

Implements semantic storage and retrieval using FAISS vector database,
with support for deduplication and relevance-based retrieval.
"""

import json
import os
import hashlib
from dataclasses import dataclass, field, asdict
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any

# Try to import FAISS, fall back to simple similarity if not available
try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False

try:
    import faiss
    HAS_FAISS = True
except ImportError:
    HAS_FAISS = False


class MemoryType(Enum):
    """Types of memories that can be stored."""
    
    # User preferences (常用应用、设置偏好等)
    USER_PREFERENCE = "user_preference"
    
    # Contact information (联系人信息)
    CONTACT = "contact"
    
    # Task patterns (任务模式和习惯)
    TASK_PATTERN = "task_pattern"
    
    # App usage (应用使用习惯)
    APP_USAGE = "app_usage"
    
    # Successful task records (成功完成的任务记录)
    TASK_HISTORY = "task_history"
    
    # User corrections (用户的纠正和反馈)
    USER_CORRECTION = "user_correction"
    
    # General knowledge (通用知识)
    GENERAL = "general"
    
    # Contact-App association with frequency (联系人-应用关联及频率)
    # 用于记录：联系某人时使用哪个应用，以及使用次数
    CONTACT_APP_BINDNG = "contact_app_binding"


@dataclass
class Memory:
    """A single memory unit."""
    
    # Unique identifier
    id: str
    
    # Memory content
    content: str
    
    # Memory type
    memory_type: MemoryType
    
    # Creation timestamp
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    
    # Last accessed timestamp
    last_accessed: str = field(default_factory=lambda: datetime.now().isoformat())
    
    # Access count (for importance scoring)
    access_count: int = 1
    
    # Relevance score (0-1, higher means more relevant)
    importance: float = 0.5
    
    # Associated metadata (app name, contact name, etc.)
    metadata: dict[str, Any] = field(default_factory=dict)
    
    # Embedding vector (for semantic search)
    embedding: list[float] | None = None
    
    def to_dict(self) -> dict:
        """Convert memory to dictionary for storage."""
        data = asdict(self)
        data["memory_type"] = self.memory_type.value
        # Don't store large embeddings in JSON metadata
        data.pop("embedding", None)
        return data
    
    @classmethod
    def from_dict(cls, data: dict) -> "Memory":
        """Create memory from dictionary."""
        data["memory_type"] = MemoryType(data["memory_type"])
        data.pop("embedding", None)
        return cls(**data)
    
    def update_access(self):
        """Update access timestamp and count."""
        self.last_accessed = datetime.now().isoformat()
        self.access_count += 1
        # Increase importance based on access frequency
        self.importance = min(1.0, self.importance + 0.05)


class SimpleEmbedder:
    """Simple text embedder using character-level features.
    
    This is a fallback when no ML models are available.
    For production use, consider using sentence-transformers or OpenAI embeddings.
    """
    
    def __init__(self, dim: int = 128):
        self.dim = dim
    
    def encode(self, texts: list[str]) -> list[list[float]]:
        """Generate simple embeddings for texts."""
        embeddings = []
        for text in texts:
            # Create a simple hash-based embedding
            emb = [0.0] * self.dim
            
            # Character frequency features
            for i, char in enumerate(text.lower()):
                idx = ord(char) % self.dim
                emb[idx] += 1.0 / (i + 1)  # Position-weighted
            
            # N-gram features
            for i in range(len(text) - 1):
                bigram = text[i:i+2]
                idx = hash(bigram) % self.dim
                emb[idx] += 0.5
            
            # Normalize
            norm = sum(x*x for x in emb) ** 0.5
            if norm > 0:
                emb = [x / norm for x in emb]
            
            embeddings.append(emb)
        
        return embeddings


class MemoryStore:
    """
    Persistent storage for agent memories using vector similarity search.
    
    Features:
    - Semantic similarity search using embeddings
    - Automatic deduplication of similar memories
    - Importance-based ranking
    - Persistent storage to disk
    """
    
    def __init__(
        self,
        storage_dir: str = "memory_db",
        embedding_dim: int = 128,
        similarity_threshold: float = 0.85,
        max_memories: int = 10000,
    ):
        """
        Initialize memory store.
        
        Args:
            storage_dir: Directory for persistent storage
            embedding_dim: Dimension of embedding vectors
            similarity_threshold: Threshold for deduplication (0-1)
            max_memories: Maximum number of memories to store
        """
        self.storage_dir = Path(storage_dir)
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        
        self.embedding_dim = embedding_dim
        self.similarity_threshold = similarity_threshold
        self.max_memories = max_memories
        
        # Initialize embedder
        self.embedder = SimpleEmbedder(dim=embedding_dim)
        
        # Memory storage
        self.memories: dict[str, Memory] = {}
        
        # FAISS index for similarity search
        self.index = None
        self.id_to_index: dict[str, int] = {}
        self.index_to_id: dict[int, str] = {}
        
        self._init_index()
        self._load_memories()
    
    def _init_index(self):
        """Initialize or recreate FAISS index."""
        if HAS_FAISS and HAS_NUMPY:
            self.index = faiss.IndexFlatIP(self.embedding_dim)  # Inner product (cosine similarity)
        else:
            self.index = None
    
    def _generate_id(self, content: str, memory_type: MemoryType) -> str:
        """Generate a unique ID for a memory."""
        hash_input = f"{content}:{memory_type.value}:{datetime.now().isoformat()}"
        return hashlib.sha256(hash_input.encode()).hexdigest()[:16]
    
    def _get_embedding(self, text: str) -> list[float]:
        """Get embedding for text."""
        embeddings = self.embedder.encode([text])
        return embeddings[0]
    
    def _compute_similarity(self, emb1: list[float], emb2: list[float]) -> float:
        """Compute cosine similarity between two embeddings."""
        if HAS_NUMPY:
            a = np.array(emb1)
            b = np.array(emb2)
            return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-8))
        else:
            # Pure Python fallback
            dot = sum(a * b for a, b in zip(emb1, emb2))
            norm1 = sum(x * x for x in emb1) ** 0.5
            norm2 = sum(x * x for x in emb2) ** 0.5
            return dot / (norm1 * norm2 + 1e-8)
    
    def add(
        self,
        content: str,
        memory_type: MemoryType,
        metadata: dict[str, Any] | None = None,
        importance: float = 0.5,
    ) -> Memory | None:
        """
        Add a new memory with automatic deduplication.
        
        Args:
            content: Memory content text
            memory_type: Type of memory
            metadata: Additional metadata
            importance: Initial importance score (0-1)
        
        Returns:
            The created Memory object, or None if deduplicated
        """
        # Generate embedding
        embedding = self._get_embedding(content)
        
        # Check for similar existing memories (deduplication)
        similar_memory = self._find_similar(embedding, memory_type)
        if similar_memory:
            # Update existing memory instead of creating new one
            similar_memory.update_access()
            # Merge metadata
            if metadata:
                similar_memory.metadata.update(metadata)
            self._save_memories()
            return similar_memory
        
        # Create new memory
        memory_id = self._generate_id(content, memory_type)
        memory = Memory(
            id=memory_id,
            content=content,
            memory_type=memory_type,
            metadata=metadata or {},
            importance=importance,
            embedding=embedding,
        )
        
        # Add to storage
        self.memories[memory_id] = memory
        
        # Add to FAISS index
        self._add_to_index(memory_id, embedding)
        
        # Check memory limit
        self._enforce_memory_limit()
        
        # Persist
        self._save_memories()
        
        return memory
    
    def _find_similar(
        self,
        embedding: list[float],
        memory_type: MemoryType | None = None,
    ) -> Memory | None:
        """Find similar existing memory for deduplication."""
        for mem_id, memory in self.memories.items():
            # Filter by type if specified
            if memory_type and memory.memory_type != memory_type:
                continue
            
            if memory.embedding:
                similarity = self._compute_similarity(embedding, memory.embedding)
                if similarity >= self.similarity_threshold:
                    return memory
        
        return None
    
    def _add_to_index(self, memory_id: str, embedding: list[float]):
        """Add embedding to FAISS index."""
        if self.index is not None and HAS_NUMPY:
            idx = len(self.id_to_index)
            self.id_to_index[memory_id] = idx
            self.index_to_id[idx] = memory_id
            
            # Add to FAISS
            emb_array = np.array([embedding], dtype=np.float32)
            faiss.normalize_L2(emb_array)  # Normalize for cosine similarity
            self.index.add(emb_array)
    
    def search(
        self,
        query: str,
        memory_types: list[MemoryType] | None = None,
        top_k: int = 5,
        min_importance: float = 0.0,
    ) -> list[Memory]:
        """
        Search for relevant memories.
        
        Args:
            query: Search query text
            memory_types: Filter by memory types
            top_k: Maximum number of results
            min_importance: Minimum importance threshold
        
        Returns:
            List of relevant memories sorted by relevance
        """
        query_embedding = self._get_embedding(query)
        
        results = []
        
        for memory in self.memories.values():
            # Filter by type
            if memory_types and memory.memory_type not in memory_types:
                continue
            
            # Filter by importance
            if memory.importance < min_importance:
                continue
            
            # Compute similarity
            if memory.embedding:
                similarity = self._compute_similarity(query_embedding, memory.embedding)
                # Combine similarity with importance for final score
                score = similarity * 0.7 + memory.importance * 0.3
                results.append((memory, score))
        
        # Sort by score
        results.sort(key=lambda x: x[1], reverse=True)
        
        # Update access for retrieved memories
        top_memories = []
        for memory, score in results[:top_k]:
            memory.update_access()
            top_memories.append(memory)
        
        if top_memories:
            self._save_memories()
        
        return top_memories
    
    def get_by_type(
        self,
        memory_type: MemoryType,
        limit: int = 10,
    ) -> list[Memory]:
        """Get memories by type, sorted by importance."""
        memories = [
            m for m in self.memories.values()
            if m.memory_type == memory_type
        ]
        memories.sort(key=lambda x: x.importance, reverse=True)
        return memories[:limit]
    
    def get_recent(self, limit: int = 10) -> list[Memory]:
        """Get most recent memories."""
        memories = list(self.memories.values())
        memories.sort(key=lambda x: x.last_accessed, reverse=True)
        return memories[:limit]
    
    def delete(self, memory_id: str) -> bool:
        """Delete a memory by ID."""
        if memory_id in self.memories:
            del self.memories[memory_id]
            # Rebuild index
            self._rebuild_index()
            self._save_memories()
            return True
        return False
    
    def clear(self, memory_type: MemoryType | None = None):
        """Clear memories, optionally by type."""
        if memory_type:
            to_delete = [
                mid for mid, m in self.memories.items()
                if m.memory_type == memory_type
            ]
            for mid in to_delete:
                del self.memories[mid]
        else:
            self.memories.clear()
        
        self._rebuild_index()
        self._save_memories()
    
    def _rebuild_index(self):
        """Rebuild FAISS index from scratch."""
        self._init_index()
        self.id_to_index.clear()
        self.index_to_id.clear()
        
        for memory_id, memory in self.memories.items():
            if memory.embedding:
                self._add_to_index(memory_id, memory.embedding)
    
    def _enforce_memory_limit(self):
        """Remove least important memories if limit exceeded."""
        if len(self.memories) <= self.max_memories:
            return
        
        # Sort by importance and recency
        memories = list(self.memories.values())
        memories.sort(
            key=lambda x: (x.importance, x.last_accessed),
            reverse=True
        )
        
        # Keep top memories
        keep_ids = {m.id for m in memories[:self.max_memories]}
        to_delete = [mid for mid in self.memories if mid not in keep_ids]
        
        for mid in to_delete:
            del self.memories[mid]
        
        self._rebuild_index()
    
    def _save_memories(self):
        """Save memories to disk."""
        # Save metadata
        metadata_path = self.storage_dir / "memories_meta.json"
        meta_data = {
            mid: m.to_dict()
            for mid, m in self.memories.items()
        }
        with open(metadata_path, "w", encoding="utf-8") as f:
            json.dump(meta_data, f, ensure_ascii=False, indent=2)
        
        # Save embeddings separately
        if HAS_NUMPY:
            embeddings_path = self.storage_dir / "embeddings.npy"
            embeddings = {}
            for mid, m in self.memories.items():
                if m.embedding:
                    embeddings[mid] = m.embedding
            np.save(embeddings_path, embeddings, allow_pickle=True)
    
    def _load_memories(self):
        """Load memories from disk."""
        metadata_path = self.storage_dir / "memories_meta.json"
        embeddings_path = self.storage_dir / "embeddings.npy"
        
        if not metadata_path.exists():
            return
        
        # Load metadata
        with open(metadata_path, "r", encoding="utf-8") as f:
            meta_data = json.load(f)
        
        # Load embeddings
        embeddings = {}
        if embeddings_path.exists() and HAS_NUMPY:
            embeddings = np.load(embeddings_path, allow_pickle=True).item()
        
        # Reconstruct memories
        for mid, data in meta_data.items():
            memory = Memory.from_dict(data)
            if mid in embeddings:
                memory.embedding = embeddings[mid]
            self.memories[mid] = memory
        
        # Rebuild index
        self._rebuild_index()
    
    def get_stats(self) -> dict:
        """Get memory store statistics."""
        type_counts = {}
        for memory in self.memories.values():
            t = memory.memory_type.value
            type_counts[t] = type_counts.get(t, 0) + 1
        
        return {
            "total_memories": len(self.memories),
            "by_type": type_counts,
            "storage_dir": str(self.storage_dir),
            "has_faiss": HAS_FAISS,
        }
    
    def export_memories(self) -> list[dict]:
        """Export all memories as list of dicts."""
        return [m.to_dict() for m in self.memories.values()]
    
    def import_memories(self, memories: list[dict]):
        """Import memories from list of dicts."""
        for data in memories:
            memory = Memory.from_dict(data)
            memory.embedding = self._get_embedding(memory.content)
            self.memories[memory.id] = memory
        
        self._rebuild_index()
        self._save_memories()


