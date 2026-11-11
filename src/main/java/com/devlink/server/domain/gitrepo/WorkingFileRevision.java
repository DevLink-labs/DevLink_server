package com.devlink.server.domain.gitrepo;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "working_file_revisions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkingFileRevision {

	@EmbeddedId
	private WorkingFileId id;
	
	@Column(name = "revision", nullable = false)
	private UUID revision;
	
}
