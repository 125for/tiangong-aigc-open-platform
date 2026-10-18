package com.tiangong.billing.controller;

import com.tiangong.billing.dto.Dtos;
import com.tiangong.billing.repo.CreditLedgerRepository;
import com.tiangong.billing.service.CreditService;
import com.tiangong.billing.service.OrderService;
import com.tiangong.common.web.CurrentUser;
import com.tiangong.common.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BillingController {

    private final OrderService orderService;
    private final CreditService creditService;
    private final CreditLedgerRepository ledgerRepo;

    @Value("${tg.jwt.secret}")
    private String jwtSecret;

    @GetMapping("/plans")
    public Result<List<Dtos.PlanVO>> plans() {
        return Result.ok(orderService.onSalePlans());
    }

    @PostMapping("/orders")
    public Result<Dtos.OrderVO> createOrder(@Valid @RequestBody Dtos.OrderCreateReq req,
                                            HttpServletRequest request) {
        return Result.ok(orderService.createOrder(CurrentUser.userId(request, jwtSecret), req.planCode()));
    }

    @GetMapping("/orders/{orderNo}")
    public Result<Dtos.OrderVO> getOrder(@PathVariable String orderNo, HttpServletRequest request) {
        return Result.ok(orderService.getByOrderNo(CurrentUser.userId(request, jwtSecret), orderNo));
    }

    @GetMapping("/credits/me")
    public Result<Dtos.CreditAccountVO> myCredits(HttpServletRequest request) {
        return Result.ok(creditService.accountOf(CurrentUser.userId(request, jwtSecret)));
    }

    @GetMapping("/ledgers/me")
    public Result<Dtos.LedgerPageVO> myLedgers(HttpServletRequest request,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        long userId = CurrentUser.userId(request, jwtSecret);
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        var rows = ledgerRepo.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        List<Dtos.LedgerVO> items = rows.stream()
                .map(l -> new Dtos.LedgerVO(l.getChangeType(), l.getAmount(), l.getBalanceAfter(),
                        l.getBizType(), l.getBizNo(), l.getRemark(), l.getCreatedAt()))
                .toList();
        return Result.ok(new Dtos.LedgerPageVO(items, pageable.getPageNumber(),
                pageable.getPageSize(), items.size()));
    }
}
