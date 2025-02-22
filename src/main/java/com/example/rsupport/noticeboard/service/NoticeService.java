package com.example.rsupport.noticeboard.service;

import com.example.rsupport.noticeboard.dto.response.NoticeCreateResponseDTO;
import com.example.rsupport.noticeboard.dto.response.NoticeUpdateResponseDTO;
import com.example.rsupport.noticeboard.exception.*;
import com.example.rsupport.noticeboard.util.FileManager;
import com.example.rsupport.noticeboard.dto.common.FileSaveResultDTO;
import com.example.rsupport.noticeboard.dto.request.NoticeCreateRequestDTO;
import com.example.rsupport.noticeboard.dto.request.NoticeUpdateRequestDTO;
import com.example.rsupport.noticeboard.dto.response.NoticeDetailResponseDTO;
import com.example.rsupport.noticeboard.dto.response.NoticeResponseDTO;
import com.example.rsupport.noticeboard.entity.FileTable;
import com.example.rsupport.noticeboard.entity.Notice;
import com.example.rsupport.noticeboard.repository.FileTableRepository;
import com.example.rsupport.noticeboard.repository.NoticeRepository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class NoticeService {

    private static final Logger logger = LoggerFactory.getLogger(NoticeService.class);

    private final NoticeRepository noticeRepository;
    private final FileManager fileManager;
    private final FileTableRepository fileTableRepository;

    @Autowired
    public NoticeService(NoticeRepository noticeRepository, FileManager fileManager, FileTableRepository fileTableRepository) {
        this.noticeRepository = noticeRepository;
        this.fileManager = fileManager;
        this.fileTableRepository = fileTableRepository;
    }

    @Transactional
    public NoticeCreateResponseDTO createNotice(NoticeCreateRequestDTO dto, MultipartFile[] files) {
        Notice createNotice = Notice.from(dto);

        if (files != null && files.length > 0) {
            try {
                addNewFiles(files, createNotice);
            } catch (Exception e) {
                logger.error("파일 저장 중 문제가 발생하였습니다.", e);
                throw new FileSaveException("file save failed.");
            }
        }

        Notice savedNotice;
        try {
            savedNotice = noticeRepository.save(createNotice);
            logger.info("공지사항 등록 성공.");
        } catch (Exception e) {
            logger.error("공지사항 등록 중 문제가 발생하였습니다.", e);
            throw new NoticeCreateException("공지사항 등록 중 문제가 발생하였습니다.");
        }

        return new NoticeCreateResponseDTO(savedNotice);
    }

    @Transactional
    public void deleteNotice(Long noticeId, String userId) {

        Notice notice = getNoticeById(noticeId);
        userIdChecker(notice, userId);
        List<FileTable> fileTables = notice.getFiles();

        try {
            if (fileTables != null && !fileTables.isEmpty()) {
                fileTableRepository.deleteAll(fileTables);
                deleteExistingFiles(notice);
            }

            noticeRepository.delete(notice);
            logger.info("공지사항 삭제 성공. ID: {}", noticeId);
        }catch (UserIdMismatchException e){
            logger.error("작성자만 삭제할 수 있습니다.", e);
            throw e;
        } catch (Exception e) {
            logger.error("공지사항 삭제 중 문제가 발생했습니다.", e);
            throw new NoticeDeleteException("공지사항 삭제 중 문제가 발생했습니다.");
        }
    }

    @Transactional
    public NoticeDetailResponseDTO getNoticeDetail(Long noticeId) {
        Notice notice = getNoticeById(noticeId);

        try {
            notice.incrementViews();
            noticeRepository.save(notice);

            return new NoticeDetailResponseDTO(notice);
        } catch (Exception e) {
            logger.error("공지사항 조회 중 문제가 발생했습니다.", e);
            throw new NoticeDetailException("공지사항 조회 중 문제가 발생했습니다.");
        }
    }

    @Transactional(readOnly = true)
    public Page<NoticeResponseDTO> getNoticeSearch(String searchType, String keyword, Pageable pageable) {
        try {
            Page<Notice> noticePage = noticeRepository.getNoticeSearch(searchType, keyword, pageable);
            return noticePage.map(NoticeResponseDTO::fromNotice);
        } catch (Exception e) {
            logger.error("공지사항 조건 검색 중 문제가 발생했습니다.", e);
            throw new NoticeSearchException("공지사항 조건 검색 중 문제가 발생했습니다.");
        }
    }

    @Transactional
    public NoticeUpdateResponseDTO updateNotice(NoticeUpdateRequestDTO dto, MultipartFile[] files) {
        try {
            Notice notice = getNoticeById(dto.getNoticeId());
            userIdChecker(notice, dto.getUserId());
            deleteExistingFiles(notice);
            updateNoticeDetails(dto, notice);
            if (files != null && files.length > 0) {
                addNewFiles(files, notice);
            }
            noticeRepository.updateNotice(notice);
            return new NoticeUpdateResponseDTO(notice);
        } catch (UserIdMismatchException e){
            logger.error("작성자만 삭제할 수 있습니다.", e);
            throw e;
        }catch (Exception e) {
            logger.error("파일 처리 중 문제가 발생하였습니다.", e);
            throw new IllegalArgumentException("파일 처리 중 문제가 발생하였습니다.", e);
        }
    }

    private Notice getNoticeById(Long noticeId) {
        return noticeRepository.findById(noticeId)
                .orElseThrow(() -> new NoticeNotFoundException("공지사항을 찾을 수 없습니다."));
    }

    private void userIdChecker(Notice notice, String userId) {
        if (!notice.getUserId().equals(userId)) {
            throw new UserIdMismatchException("작성자만 수정할 수 있습니다.");
        }
    }

    private void deleteExistingFiles(Notice notice) {
        List<FileTable> existingFiles = notice.getFiles();
        fileTableRepository.deleteAll(existingFiles);
        logger.info("기존 파일 내역 DB 삭제 성공. 삭제된 파일 수: {}", existingFiles.size());

        List<String> existingFilePaths = existingFiles.stream()
                .map(file -> file.getFilePath() + '/' + file.getFileName())
                        .toList();
        fileManager.deleteFiles(existingFilePaths);
        logger.info("기존 파일 삭제 성공");

        notice.setFiles(new ArrayList<>());
    }

    private void updateNoticeDetails(NoticeUpdateRequestDTO dto, Notice notice) {
        dto.updateNotice(notice);
    }

    private void addNewFiles(MultipartFile[] files, Notice notice) {
        try {
            List<FileSaveResultDTO> savedFiles = fileManager.saveFile(files);
            List<FileTable> fileTables = savedFiles.stream()
                    .map(file -> new FileTable(file.getFileName(), file.getFilePath(), notice))
                    .toList();
            if (notice.getFiles() != null) {
                notice.getFiles().addAll(fileTables); // 기존 리스트에 새 파일을 추가합니다.
            } else {
                notice.setFiles(fileTables); // 파일 목록이 null인 경우 새 리스트로 설정합니다.
            }
        } catch (Exception e) {
            logger.error("새 파일을 추가하는 중 문제가 발생했습니다.", e);
            throw new FileSaveException("새 파일을 추가하는 중 문제가 발생했습니다.");
        }
    }
}