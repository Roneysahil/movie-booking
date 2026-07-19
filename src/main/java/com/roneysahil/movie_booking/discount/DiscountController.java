package com.roneysahil.movie_booking.discount;

import com.roneysahil.movie_booking.discount.DiscountDtos.CreateDiscountRequest;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountCodeDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.DiscountPreviewDto;
import com.roneysahil.movie_booking.discount.DiscountDtos.ValidateDiscountRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DiscountController {

    private final DiscountService discounts;

    public DiscountController(DiscountService discounts) {
        this.discounts = discounts;
    }

    /** Preview a code before committing. Advisory only; booking re-validates. */
    @PostMapping("/discounts/validate")
    @PreAuthorize("hasRole('CUSTOMER')")
    public DiscountPreviewDto validate(
            Principal principal, @Valid @RequestBody ValidateDiscountRequest request) {
        return discounts.validate(principal.getName(), request);
    }

    @PostMapping("/admin/discounts")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscountCodeDto create(@Valid @RequestBody CreateDiscountRequest request) {
        return discounts.createCode(request);
    }

    @GetMapping("/admin/discounts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DiscountCodeDto> list() {
        return discounts.listCodes();
    }
}
